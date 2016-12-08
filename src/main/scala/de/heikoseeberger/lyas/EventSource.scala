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

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.Get
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse, Uri }
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.{ Flow, GraphDSL, Merge, Sink, Source, Unzip }
import akka.stream.{ ActorMaterializer, Materializer, SourceShape }
import de.heikoseeberger.akkasse.MediaTypes.`text/event-stream`
import de.heikoseeberger.akkasse.headers.`Last-Event-ID`
import de.heikoseeberger.akkasse.{ EventStreamUnmarshalling, ServerSentEvent }
import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Future, Promise }

/**
  * This stream processing stage establishes a continuous source of server-sent
  * events from the given URI.
  *
  * A single source of server-sent events is obtained from the URI. Once
  * completed, either normally or by failure, a next one is obtained thereby
  * sending a Last-Evend-ID header if available. This continues in an endless
  * cycle.
  *
  * The shape of this processing stage is a source of server-sent events; to
  * take effect it must be connected and run. Progress (including termination)
  * is controlled by the connected flow or sink, e.g. a retry delay can be
  * implemented by streaming the materialized values of the handler via a
  * throttle.
  *
  *{{{
  * + - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - +
  *                                                       +---------------------+
  * |                                                     |       trigger       | |
  *                                                       +----------o----------+
  * |                                                                |            |
  *                                                    Option[String]|
  * |                                                                v            |
  *              Option[String]                           +----------o----------+
  * |            +--------------------------------------->o        merge        | |
  *              |                                        +----------o----------+
  * |            |                                                   |            |
  *              |                                     Option[String]|
  * |            |                                                   v            |
  *   +----------o----------+                             +----------o----------+
  * | | currentLastEventId  |                             |    eventSources     | |
  *   +----------o----------+                             +----------o----------+
  * |            ^                                                   |            |
  *              |  (Source[ServerSentEvent, Any], Future[LastEvent])|
  * |            |                                                   v            |
  *              |                                        +----------o----------+
  * |            +----------------------------------------o        unzip        | |
  *              Future[LastEvent]                        +----------o----------+
  * |                                                                |            |
  *                                      Source[ServerSentEvent, Any]|
  * |                                                                v            |
  *                                                       +----------o----------+
  * |                                      +--------------o       flatten       | |
  *                         ServerSentEvent|              +---------------------+
  * |                                      v                                      |
  *  - - - - - - - - - - - - - - - - - - - o - - - - - - - - - - - - - - - - - - -
  *}}}
  */
object EventSource {

  private type EventSource = Source[ServerSentEvent, Any]

  private val noEvents = Future.successful(Source.empty[ServerSentEvent])

  /**
    * @param uri URI with absolute path, e.g. "http://myserver/events
    * @param send function to send a HTTP request
    * @param lastEventId initial value for Last-Evend-ID header, optional
    * @param mat implicit `Materializer`
    * @return continuous source of server-sent events
    */
  def apply(uri: Uri,
            send: HttpRequest => Future[HttpResponse],
            lastEventId: Option[String] = None)(
      implicit mat: Materializer): EventSource = {
    import mat.executionContext

    val eventSources = {
      def getEventSource(lastEventId: Option[String]) = {
        import EventStreamUnmarshalling._
        val request = {
          val r = Get(uri).addHeader(Accept(`text/event-stream`))
          lastEventId.foldLeft(r)((r, i) => r.addHeader(`Last-Event-ID`(i)))
        }
        send(request)
          .flatMap(Unmarshal(_).to[EventSource])
          .fallbackTo(noEvents)
      }
      def enrichWithLastEvent(eventSource: EventSource) = {
        val p = Promise[Option[String]]()
        val enriched =
          eventSource.alsoToMat(Sink.lastOption) {
            case (m, f) =>
              p.completeWith(f.map(_.flatMap(_.id)))
              m
          }
        (enriched, p.future)
      }
      Flow[Option[String]].mapAsync(1)(getEventSource).map(enrichWithLastEvent)
    }

    Source.fromGraph(GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._
      val trigger = builder.add(Source.single(lastEventId))
      val unzip   = builder.add(Unzip[EventSource, Future[Option[String]]]())
      val flatten = builder.add(Flow[EventSource].flatMapConcat(identity))
      // format: OFF
                                 unzip.out0 ~> flatten
      trigger ~> eventSources ~> unzip.in
                                 unzip.out1 ~> Sink.ignore
      // format: ON
      SourceShape(flatten.out)
    })
  }
}

object EventSourceApp {

  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem()
    implicit val mat    = ActorMaterializer()
    import system.dispatcher

    val config  = system.settings.config
    val address = config.getString("lyas-server.address")
    val port    = config.getInt("lyas-server.port")

    def printEvent(event: ServerSentEvent) = {
      val id   = event.id.getOrElse("")
      val data = event.data.getOrElse("")
      println(s"{ id: $id, data: $data }")
    }

    val eventSource = EventSource(Uri(s"http://$address:$port"),
                                  Http().singleRequest(_),
                                  Some("10"))
    eventSource.runForeach(printEvent).onComplete(_ => system.terminate())

    Await.ready(system.whenTerminated, Duration.Inf)
  }
}
