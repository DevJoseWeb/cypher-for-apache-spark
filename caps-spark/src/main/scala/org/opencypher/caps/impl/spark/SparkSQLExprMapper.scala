/*
 * Copyright (c) 2016-2018 "Neo4j, Inc." [https://neo4j.com]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opencypher.caps.impl.spark

import CAPSFunctions._
import org.apache.spark.sql._
import org.apache.spark.sql.types._
import org.opencypher.caps.api.exception._
import org.opencypher.caps.api.types._
import org.opencypher.caps.api.value.CypherValue._
import org.opencypher.caps.impl.record.RecordHeader
import org.opencypher.caps.impl.spark.convert.SparkUtils._
import org.opencypher.caps.impl.spark.physical.RuntimeContext
import org.opencypher.caps.impl.spark.physical.operators.PhysicalOperator.columnName
import org.opencypher.caps.ir.api.expr._

object SparkSQLExprMapper {

  implicit class RichExpression(expr: Expr) {

    def verify(implicit header: RecordHeader) = {
      val slots = header.slotsFor(expr)
      if (slots.isEmpty) {
        throw IllegalStateException(s"No slot for expression $expr")
      } else if (slots.size > 1 && !expr.isInstanceOf[Var]) {
        throw NotImplementedException("Support for multi-column expressions")
      }
    }

    def column(implicit header: RecordHeader, dataFrame: DataFrame, context: RuntimeContext): Column = {
      expr match {
        case p@Param(name) if p.cypherType.subTypeOf(CTList(CTAny)).maybeTrue =>
          context.parameters(name) match {
            case CypherList(l) => functions.array(l.unwrap.map(functions.lit(_)): _*)
            case notAList => throw IllegalArgumentException("a Cypher list", notAList)
          }
        case Param(name) =>
          functions.lit(context.parameters(name).unwrap)
        case ListLit(exprs) =>
          val cols = exprs.map(_.column)
          functions.array(cols: _*)
        case l: Lit[_] =>
          functions.lit(l.v)
        case _ =>
          verify

          val slot = header.slotsFor(expr).head

          val columns = dataFrame.columns.toSet
          val colName = columnName(slot)

          if (columns.contains(colName)) {
            dataFrame.col(colName)
          } else {
            functions.lit(null)
          }
      }

    }

    /**
      * This is possible without violating Cypher semantics because
      *   - Spark SQL returns null when comparing across types (from initial investigation)
      *   - We never have multiple types per column in CAPS (yet)
      */
    def compare(comparator: Column => (Column => Column), lhs: Expr, rhs: Expr)
      (implicit header: RecordHeader, df: DataFrame, context: RuntimeContext): Column = {
      comparator(lhs.column)(rhs.column)
    }

    def lt(c: Column): Column => Column = c < _

    def lteq(c: Column): Column => Column = c <= _

    def gt(c: Column): Column => Column = c > _

    def gteq(c: Column): Column => Column = c >= _

    /**
      * Attempts to create a Spark SQL expression from the CAPS expression.
      *
      * @param header  the header of the CAPSRecords in which the expression should be evaluated.
      * @param df      the dataframe containing the data over which the expression should be evaluated.
      * @param context context with helper functions, such as column names.
      * @return Some Spark SQL expression if the input was mappable, otherwise None.
      */
    def asSparkSQLExpr(implicit header: RecordHeader, df: DataFrame, context: RuntimeContext): Option[Column] = {

      expr match {
        case ListLit(exprs) =>
          val cols = exprs.map(_.asSparkSQLExpr.getOrElse(functions.lit(null)))
          Some(functions.array(cols: _*))

        case _: Var | _: Param | _: Lit[_] | _: Property => Some(expr.column)

        // predicates
        case Equals(e1, e2) => Some(e1.column === e2.column)
        case Not(e) => e.asSparkSQLExpr.map(!_)
        case IsNull(e) => Some(e.column.isNull)
        case IsNotNull(e) => Some(e.column.isNotNull)
        case Size(e) =>
          val col = e.column
          e.cypherType match {
            case CTString => Some(functions.length(col).cast(LongType))
            case _: CTList => Some(functions.size(col).cast(LongType))
            case other => throw NotImplementedException(s"size() on type $other")
          }

        case Ands(exprs) =>
          val cols = exprs.map(_.asSparkSQLExpr)
          if (cols.contains(None)) None
          else Some(cols.flatten.foldLeft(functions.lit(true))(_ && _))

        case Ors(exprs) =>
          val cols = exprs.map(_.asSparkSQLExpr)
          if (cols.contains(None)) None
          else Some(cols.flatten.foldLeft(functions.lit(false))(_ || _))

        case In(lhs, rhs) =>
          val element = lhs.column
          val array = rhs.column
          Some(array_contains(array, element))

        case HasType(rel, relType) =>
          val col = Type(rel)().column
          Some(col === relType.name)

        case h: HasLabel => Some(h.column) // it's a boolean column

        case LessThan(lhs, rhs) => Some(compare(lt _, lhs, rhs))
        case LessThanOrEqual(lhs, rhs) => Some(compare(lteq _, lhs, rhs))
        case GreaterThanOrEqual(lhs, rhs) => Some(compare(gteq _, lhs, rhs))
        case GreaterThan(lhs, rhs) => Some(compare(gt _, lhs, rhs))

        // Arithmetics
        case Add(lhs, rhs) => Some(lhs.column + rhs.column)
        case Subtract(lhs, rhs) => Some(lhs.column - rhs.column)
        case Multiply(lhs, rhs) => Some(lhs.column * rhs.column)
        case div@Divide(lhs, rhs) => Some((lhs.column / rhs.column).cast(toSparkType(div.cypherType)))

        // Functions
        case Exists(e) => Some(e.column.isNotNull)
        case Id(e) => Some(e.column)
        case Labels(e) =>
          val node = Var(columnName(header.slotsFor(e).head))(CTNode)
          val labelExprs = header.labels(node)
          val labelColumns = labelExprs.map(_.column)
          val labelNames = labelExprs.map(_.label.name)
          val booleanLabelFlagColumn = functions.array(labelColumns: _*)
          Some(get_node_labels(labelNames)(booleanLabelFlagColumn))

        case Keys(e) =>
          val node = Var(columnName(header.slotsFor(e).head))(CTNode)
          val propertyExprs = header.properties(node)
          val propertyColumns = propertyExprs.map(_.column)
          val keyNames = propertyExprs.map(_.key.name)
          val valuesColumn = functions.array(propertyColumns: _*)
          Some(get_property_keys(keyNames)(valuesColumn))

        case Type(inner) =>
          inner match {
            case v: Var =>
              val typeSlot = header.typeSlot(v)
              val typeCol = df.col(columnName(typeSlot))
              Some(typeCol)
            case _ => throw NotImplementedException("type() of non-variables")
          }

        case StartNodeFunction(e) =>
          val rel = Var(columnName(header.slotsFor(e).head))(CTNode)
          Some(header.sourceNodeSlot(rel).content.key.column)

        case EndNodeFunction(e) =>
          val rel = Var(columnName(header.slotsFor(e).head))(CTNode)
          Some(header.targetNodeSlot(rel).content.key.column)

        case ToFloat(e) => Some(e.column.cast(DoubleType))

        // Pattern Predicate
        case ep: ExistsPatternExpr => Some(ep.targetField.column)

        case Coalesce(es) =>
          val columns = es.map(_.column)
          Some(functions.coalesce(columns: _*))

        case _ => None
      }
    }
  }

}
