/*
 * Copyright 2019 ABSA Group Limited
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

package za.co.absa.spline.harvester.dispatcher


import org.apache.commons.configuration.Configuration
import org.apache.spark.internal.Logging
import scalaj.http.{BaseHttp, Http}
import za.co.absa.commons.config.ConfigurationImplicits._
import za.co.absa.spline.harvester.dispatcher.HttpLineageDispatcher.RESTResource
import za.co.absa.spline.harvester.exception.SplineNotInitializedException
import za.co.absa.spline.harvester.json.HarvesterJsonSerDe.impl._
import za.co.absa.spline.producer.model.{ExecutionEvent, ExecutionPlan}

import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

object HttpLineageDispatcher {
  val producerUrlProperty = "spline.producer.url"

  object RESTResource {
    val ExecutionPlans = "execution-plans"
    val ExecutionEvents = "execution-events"
    val Status = "status"
  }
}

class HttpLineageDispatcher(splineServerRESTEndpointBaseURL: String, http: BaseHttp)
  extends LineageDispatcher
    with Logging {

  def this(configuration: Configuration) = this(configuration.getRequiredString(HttpLineageDispatcher.producerUrlProperty), Http)

  log.info(s"spline.producer.url is set to:'${splineServerRESTEndpointBaseURL}'")

  val executionPlansUrl = s"$splineServerRESTEndpointBaseURL/${RESTResource.ExecutionPlans}"
  val executionEventsUrl = s"$splineServerRESTEndpointBaseURL/${RESTResource.ExecutionEvents}"
  val statusUrl = s"$splineServerRESTEndpointBaseURL/${RESTResource.Status}"


  override def send(executionPlan: ExecutionPlan): String = {
    sendJson(executionPlan.toJson, executionPlansUrl)
  }

  override def send(event: ExecutionEvent): Unit = {
    sendJson(Seq(event).toJson, executionEventsUrl)
  }

  private def sendJson(json: String, url: String) = {
    log.debug(s"sendJson $url : $json")
    try http(url)
      .postData(json)
      .compress(true)
      .header("content-type", "application/json")
      .asString
      .throwError
      .body
    catch {
      case NonFatal(e) => throw new RuntimeException(s"Cannot send lineage data to $url", e)
    }
  }

  override def ensureProducerReady(): Unit = {
    val tryStatusOk = Try(http(statusUrl)
      .method("HEAD")
      .asString
      .isSuccess)

    tryStatusOk match {
      case Success(false) => throw new SplineNotInitializedException(
        "Connection to Spline Gateway: OK, but the Gateway is not initialized properly! Check Gateway's logs.")
      case Failure(e) if NonFatal(e) => throw new SplineNotInitializedException(
        "Spark Agent was not able to establish connection to Spline Gateway.", e)
      case _ => Unit
    }
  }
}
