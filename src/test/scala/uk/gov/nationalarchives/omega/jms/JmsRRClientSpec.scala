/*
 * Copyright (c) 2023 The National Archives
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package uk.gov.nationalarchives.omega.jms

import cats.effect.std.Queue
import cats.effect.{IO, Resource}
import munit.CatsEffectSuite
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jFactory
import uk.gov.nationalarchives.omega.jms.JmsRRClient.ReplyMessageHandler

class JmsRRClientSpec extends CatsEffectSuite {

  test("request reply test") {

    val logging = Slf4jFactory[IO]
    implicit val logger: SelfAwareStructuredLogger[IO] = logging.getLogger

    val requestQueue = "request-general"
    val replyQueue = "omega-editorial-web-application-instance-1"

    // Create a Jms Request-Reply Client (e.g. from the Play Application start up method)
    val clientRes: Resource[IO, JmsRRClient[IO]] = JmsRRClient.createForSqs[IO](
      HostBrokerEndpoint("localhost", 9324),
      UsernamePasswordCredentials("x", "x"),
      None
    )(replyQueue)

    // setup for cats-effect
    val (jmsRrClient, closer) = clientRes.allocated.unsafeRunSync()

    // The following block can be used in a function within the service layer of a Play Framework application.
    // It works by creating a Queue and putting it into the ReplyMessageHandler. It next makes a request using the JMS
    // client and when the reply eventually appears in the queue in can be returned to the caller of the service
    // function (e.g. a Play controller) where it can be converted to a Future[Result].
    val result = Queue.bounded[IO, String](1).flatMap { editSetQueue => {
      val replyHandler: ReplyMessageHandler[IO] = replyMessage => editSetQueue.offer(replyMessage.body)
      jmsRrClient.request(requestQueue, RequestMessage(s"hello 1234","1234"), replyHandler)flatMap { _ =>
        editSetQueue.take
      }
    }}.unsafeRunSync()

    IO(result).assertEquals("Echo Server: hello 1234","Result not as expected").unsafeRunSync()

    // 3) finally run the closer (e.g. from the Play Application shutdown method)
    closer.unsafeRunSync()
  }

}
