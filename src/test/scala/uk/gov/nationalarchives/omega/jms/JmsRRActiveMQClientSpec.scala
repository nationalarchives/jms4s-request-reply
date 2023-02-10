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

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{IO, Resource}
import org.scalatest.FutureOutcome
import org.scalatest.freespec.FixtureAsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jFactory

class JmsRRActiveMQClientSpec extends FixtureAsyncFreeSpec with AsyncIOSpec with Matchers  {

  /* TODO(RW) the ActiveMQ tests are not working currently */

  override type FixtureParam = JmsRRClient[IO]

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    // setup for cats-effect
    val logging = Slf4jFactory[IO]
    implicit val logger: SelfAwareStructuredLogger[IO] = logging.getLogger
    val replyQueue = "omega-editorial-web-application-instance-1"
    // Create a Jms Request-Reply Client (e.g. from the Play Application start up method)
    val clientRes: Resource[IO, JmsRRClient[IO]] = JmsRRClient.createForActiveMq[IO](
      HostBrokerEndpoint("localhost", 61616),
      UsernamePasswordCredentials("admin", "passw0rd"),
      None
    )(replyQueue)
    val (jmsRrClient, closer) = clientRes.allocated.unsafeRunSync()
    complete {
      super.withFixture(test.toNoArgAsyncTest(jmsRrClient))
    } lastly {
      closer.unsafeRunSync()
    }
  }

  "ActiveMQ Client" - {
    "send a message and handle the reply" ignore { jmsRrClient =>
      val requestQueue = "request-general"
      val handler = RequestReplyHandler(jmsRrClient)
      val result = handler.handle(requestQueue, RequestMessage(s"hello 1234", "1234"))

      IO(result.unsafeRunSync()).asserting(_ mustBe "Echo Server: hello 1234")
    }
    "send two messages and handle the replies" ignore { jmsRrClient =>
      val requestQueue = "request-general"
      val handler = RequestReplyHandler(jmsRrClient)
      val result1 = handler.handle(requestQueue, RequestMessage(s"hello 1234", "1234"))
      val result2 = handler.handle(requestQueue, RequestMessage(s"hello 5678", "5678"))

      IO(result1.unsafeRunSync()).asserting(_ mustBe "Echo Server: hello 1234")
      IO(result2.unsafeRunSync()).asserting(_ mustBe "Echo Server: hello 5678")

    }
    "send three messages and handle the replies" ignore { jmsRrClient =>
      val requestQueue = "request-general"
      val handler = RequestReplyHandler(jmsRrClient)
      val result1 = handler.handle(requestQueue, RequestMessage(s"hello 1234", "1234"))
      val result2 = handler.handle(requestQueue, RequestMessage(s"hello 5678", "5678"))
      val result3 = handler.handle(requestQueue, RequestMessage(s"hello 9000", "9000"))

      IO(result1.unsafeRunSync()).asserting(_ mustBe "Echo Server: hello 1234")
      IO(result2.unsafeRunSync()).asserting(_ mustBe "Echo Server: hello 5678")
      IO(result3.unsafeRunSync()).asserting(_ mustBe "Echo Server: hello 9000")

    }
  }

}
