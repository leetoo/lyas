/*
 * Copyright 2016 Heiko Seeberger
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

package de.heikoseeberger.lyas

import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.StatusCodes.{ BadRequest, OK }
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.Source
import de.heikoseeberger.akkasse.MediaTypes.`text/event-stream`
import de.heikoseeberger.akkasse.{ EventStreamUnmarshalling, ServerSentEvent }
import de.heikoseeberger.akkasse.headers.`Last-Event-ID`

class SseServerSpec extends BaseSpec {
  import EventStreamUnmarshalling._
  import RequestBuilding._

  private final val Address    = "localhost"
  private final val NrOfEvents = 42

  "SseServer" should {
    "respond to GET / with an appropriate source of server-sent events" in {
      val port = 10001
      SseServer(Address, port, 100, NrOfEvents)

      Http()
        .singleRequest(request(port, "10"))
        .flatMap { response =>
          response.status shouldBe OK
          response.entity.contentType.mediaType shouldBe `text/event-stream`
          Unmarshal(response)
            .to[Source[ServerSentEvent, Any]]
            .flatMap(_.runFold(Vector.empty[String])(_ :+ _.data))
        }
        .map(_ shouldBe 1.to(NrOfEvents).map(_ + 10).map(n => s"SSE-$n"))
    }

    "respond to GET / with an illegal last event id header with BadRequest" in {
      val port = 10002
      SseServer(Address, port, 100, NrOfEvents)

      Http()
        .singleRequest(request(port, "abc"))
        .map(_.status shouldBe BadRequest)
    }
  }

  private def request(port: Int, lastEventId: String) =
    Get(s"http://$Address:$port").withHeaders(`Last-Event-ID`(lastEventId))
}