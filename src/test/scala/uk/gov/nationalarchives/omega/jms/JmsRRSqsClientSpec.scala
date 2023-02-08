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
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{IO, Resource}
import org.scalatest.FutureOutcome
import org.scalatest.freespec.FixtureAsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jFactory
import uk.gov.nationalarchives.omega.jms.JmsRRClient.ReplyMessageHandler

class JmsRRSqsClientSpec extends FixtureAsyncFreeSpec with AsyncIOSpec with Matchers {

  override type FixtureParam = JmsRRClient[IO]

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    // setup for cats-effect
    val logging = Slf4jFactory[IO]
    implicit val logger: SelfAwareStructuredLogger[IO] = logging.getLogger
    val replyQueue = "omega-editorial-web-application-instance-1"
    // Create a Jms Request-Reply Client (e.g. from the Play Application start up method)
    val clientRes: Resource[IO, JmsRRClient[IO]] = JmsRRClient.createForSqs[IO](
      HostBrokerEndpoint("localhost", 9324),
      UsernamePasswordCredentials("x", "x"),
      None
    )(replyQueue)
    val (jmsRrClient, closer) = clientRes.allocated.unsafeRunSync()
    complete {
      super.withFixture(test.toNoArgAsyncTest(jmsRrClient))
    } lastly {
      closer.unsafeRunSync()
    }
  }

  "SQS Client" - {
    "send a message and handle the reply" in { jmsRrClient =>
      val requestQueue = "request-general"
      // The following block can be used in a function within the service layer of a Play Framework application.
      // It works by creating a Queue and putting it into the ReplyMessageHandler. It next makes a request using the JMS
      // client and when the reply eventually appears in the queue in can be returned to the caller of the service
      // function (e.g. a Play controller) where it can be converted to a Future[Result].
      val result = Queue.bounded[IO, String](1).flatMap { editSetQueue =>
        val replyHandler: ReplyMessageHandler[IO] = replyMessage => editSetQueue.offer(replyMessage.body)
        jmsRrClient.request(requestQueue, RequestMessage(s"hello 1234", "1234"), replyHandler) flatMap { _ =>
          editSetQueue.take
        }
      }

      IO(result.unsafeRunSync()).asserting(_ mustBe "Echo Server: hello 1234")
    }
    "send two messages and handle the replies" in { jmsRrClient =>
      val requestQueue = "request-general"

      // The following block can be used in a function within the service layer of a Play Framework application.
      // It works by creating a Queue and putting it into the ReplyMessageHandler. It next makes a request using the JMS
      // client and when the reply eventually appears in the queue in can be returned to the caller of the service
      // function (e.g. a Play controller) where it can be converted to a Future[Result].
      val result1 = Queue.bounded[IO, String](1).flatMap { editSetQueue =>
        val replyHandler: ReplyMessageHandler[IO] = replyMessage => editSetQueue.offer(replyMessage.body)
        jmsRrClient.request(requestQueue, RequestMessage(s"hello 1234", "1234"), replyHandler) flatMap { _ =>
          editSetQueue.take
        }
      }
      val result2 = Queue.bounded[IO, String](1).flatMap { editSetQueue =>
        val replyHandler: ReplyMessageHandler[IO] = replyMessage => editSetQueue.offer(replyMessage.body)
        jmsRrClient.request(requestQueue, RequestMessage(s"hello 5678", "5678"), replyHandler) flatMap { _ =>
          editSetQueue.take
        }
      }

      IO(result1.unsafeRunSync()).asserting(_ mustBe "Echo Server: hello 1234")
      IO(result2.unsafeRunSync()).asserting(_ mustBe "Echo Server: hello 5678")

    }
    "send three messages and handle the replies" in { jmsRrClient =>
      val requestQueue = "request-general"

      // The following block can be used in a function within the service layer of a Play Framework application.
      // It works by creating a Queue and putting it into the ReplyMessageHandler. It next makes a request using the JMS
      // client and when the reply eventually appears in the queue in can be returned to the caller of the service
      // function (e.g. a Play controller) where it can be converted to a Future[Result].
      val result1 = Queue.bounded[IO, String](1).flatMap { editSetQueue =>
        val replyHandler: ReplyMessageHandler[IO] = replyMessage => editSetQueue.offer(replyMessage.body)
        jmsRrClient.request(requestQueue, RequestMessage(s"hello 1234", "1234"), replyHandler) flatMap { _ =>
          editSetQueue.take
        }
      }
      val result2 = Queue.bounded[IO, String](1).flatMap { editSetQueue =>
        val replyHandler: ReplyMessageHandler[IO] = replyMessage => editSetQueue.offer(replyMessage.body)
        jmsRrClient.request(requestQueue, RequestMessage(s"hello 5678", "5678"), replyHandler) flatMap { _ =>
          editSetQueue.take
        }
      }
      val result3 = Queue.bounded[IO, String](1).flatMap { editSetQueue =>
        val replyHandler: ReplyMessageHandler[IO] = replyMessage => editSetQueue.offer(replyMessage.body)
        jmsRrClient.request(requestQueue, RequestMessage(s"hello 9000", "9000"), replyHandler) flatMap { _ =>
          editSetQueue.take
        }
      }

      IO(result1.unsafeRunSync()).asserting(_ mustBe "Echo Server: hello 1234")
      IO(result2.unsafeRunSync()).asserting(_ mustBe "Echo Server: hello 5678")
      IO(result3.unsafeRunSync()).asserting(_ mustBe "Echo Server: hello 9000")

    }
  }

}
