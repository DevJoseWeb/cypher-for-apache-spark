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
package org.opencypher.caps.impl.io

import org.mockito.Mockito._
import org.opencypher.caps.api.graph.{GraphName, PropertyGraph}
import org.opencypher.caps.api.schema.Schema
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FunSuite, Matchers}

class SessionPropertyGraphDataSourceTest extends FunSuite with MockitoSugar with Matchers {

  test("hasGraph should return true for existing graph") {
    val source = new SessionPropertyGraphDataSource
    val testGraphName = GraphName.from("test")
    source.store(testGraphName, null)
    source.hasGraph(testGraphName) should be
    true
  }

  test("hasGraph should return false for non-existing graph") {
    val source = new SessionPropertyGraphDataSource
    val testGraphName = GraphName.from("test")
    source.hasGraph(testGraphName) should be
    false
  }

  test("graph should return graph for existing graph") {
    val source = new SessionPropertyGraphDataSource
    val testGraphName = GraphName.from("test")
    val testGraph = mock[PropertyGraph]
    source.store(testGraphName, testGraph)
    source.graph(testGraphName) should be(testGraph)
  }

  test("graph should throw exception for non-existing graph") {
    val source = new SessionPropertyGraphDataSource
    val testGraphName = GraphName.from("test")
    an[NoSuchElementException] should be thrownBy source.graph(testGraphName)
  }

  test("schema should return None for non-existing graph") {
    val source = new SessionPropertyGraphDataSource
    val testGraphName = GraphName.from("test")
    source.schema(testGraphName).isEmpty should be
    true
  }

  test("schema should return schema for existing graph") {
    val source = new SessionPropertyGraphDataSource
    val testGraphName = GraphName.from("test")
    val propertyGraph = mock[PropertyGraph]
    when(propertyGraph.schema).thenReturn(Schema.empty.withRelationshipType("foo"))
    source.store(testGraphName, propertyGraph)
    source.schema(testGraphName).get should be(Schema.empty.withRelationshipType("foo"))
  }

  test("graphNames should return all names of stored graphs") {
    val source = new SessionPropertyGraphDataSource
    val testGraphName1 = GraphName.from("test1")
    val testGraphName2 = GraphName.from("test2")

    source.graphNames should equal(Set.empty)

    source.store(testGraphName1, null)
    source.store(testGraphName2, null)
    source.graphNames should equal(Set(testGraphName1, testGraphName2))
  }

}
