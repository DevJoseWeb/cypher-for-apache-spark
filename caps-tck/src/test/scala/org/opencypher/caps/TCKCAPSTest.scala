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
package org.opencypher.caps

import java.io.File

import org.opencypher.caps.impl.spark.CAPSGraph
import org.opencypher.caps.test.CAPSTestSuite
import org.opencypher.caps.test.support.creation.caps.{CAPSGraphFactory, CAPSScanGraphFactory}
import org.opencypher.tools.tck.api.{CypherTCK, Scenario}
import org.scalatest.Tag
import org.scalatest.prop.TableDrivenPropertyChecks._

import scala.util.{Failure, Success, Try}

// needs to be a val because of a TCK bug (scenarios can only be read once)
object TCKFixture {
  // TODO: enable flaky test once new TCk release is there
  val scenarios: Seq[Scenario] = CypherTCK.allTckScenarios.filterNot(_.name == "Limit to two hits")
}

class TCKCAPSTest extends CAPSTestSuite {
  import TCKFixture._

  object WhiteList extends Tag("WhiteList Scenario")

  object BlackList extends Tag("BlackList Scenario")

  val factories = Table(
    "factory",
    CAPSScanGraphFactory
  )

  val defaultFactory: CAPSGraphFactory = CAPSScanGraphFactory


  implicit class Scenarios(scenarios: Seq[Scenario]) {
    /**
      * Scenario outlines are parameterised scenarios that all have the same name, but different parameters.
      * Because test names need to be unique, we enumerate these scenarios and put the enumeration into the
      * scenario name to make those names unique.
      */
    def enumerateScenarioOutlines: Seq[Scenario] = {
      scenarios.groupBy(_.toString).flatMap { case (_, nameCollisionGroup) =>
        if (nameCollisionGroup.size <= 1) {
          nameCollisionGroup
        } else {
          nameCollisionGroup.zipWithIndex.map { case (groupScenario, index) =>
            groupScenario.copy(name = s"${groupScenario.name} #${index + 1}")
          }
        }
      }
    }.toSeq
  }

  val whiteListScenarios = Table(
    "scenario",
    scenarios.filterNot { s =>
      ScenarioBlacklist.contains(s.toString())
    }.enumerateScenarioOutlines: _*
  )

  val blackListScenarios = Table(
    "scenario",
    scenarios.filter { s =>
      ScenarioBlacklist.contains(s.toString())
    }.enumerateScenarioOutlines: _*
  )

  // white list tests are run on all factories
  forAll(factories) { factory =>
    forAll(whiteListScenarios) { scenario =>
      test(s"[${factory.name}, ${WhiteList.name}] $scenario", WhiteList) {
        scenario(TCKGraph(factory, CAPSGraph.empty)).execute()
      }
    }
  }

  // black list tests are run on default factory
  forAll(blackListScenarios) { scenario =>
    test(s"[${defaultFactory.name}, ${BlackList.name}] $scenario", BlackList) {
      val tckGraph = TCKGraph(defaultFactory, CAPSGraph.empty)

      Try(scenario(tckGraph).execute()) match {
        case Success(_) =>
          throw new RuntimeException(s"A blacklisted scenario actually worked: $scenario")
        case Failure(_) =>
          ()
      }
    }
  }

  ignore("run Custom Scenario") {
    val file = new File(getClass.getResource("CAPSTestFeature.feature").toURI)

    CypherTCK
      .parseFilesystemFeature(file)
      .scenarios
      .foreach(scenario => scenario(TCKGraph(defaultFactory, CAPSGraph.empty)).execute())
  }

  ignore("run Single Scenario") {
    val name = "A simple pattern with one bound endpoint"
    scenarios
      .filter(s => s.name == name)
      .foreach(scenario => scenario(TCKGraph(defaultFactory, CAPSGraph.empty)).execute())
  }
}