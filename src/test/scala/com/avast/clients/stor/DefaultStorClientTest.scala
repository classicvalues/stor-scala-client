package com.avast.clients.stor

import cats.effect.IO
import com.avast.clients.stor.TestImplicits._
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.http4s.client.blaze.Http1Client
import org.http4s.dsl.io._
import org.http4s.headers.{`Content-Length`, Authorization}
import org.http4s.server.blaze.BlazeBuilder
import org.http4s.{AuthScheme, Credentials, HttpService, Uri}
import org.scalatest.FunSuite
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import org.scalatest.time.{Seconds, Span}

class DefaultStorClientTest extends FunSuite with ScalaFutures with MockitoSugar {
  implicit val p: PatienceConfig = PatienceConfig(timeout = Span(5, Seconds))

  test("head") {
    val fileSize = 1000000
    val content = randomString(fileSize)
    val sha = content.sha256

    val service = HttpService[IO] {
      case request @ HEAD -> fileSha =>
        request
          .as[String]
          .flatMap { body =>
            assertResult(sha)(Sha256(fileSha.toList.head))
            assertResult(0)(body.length)

            Ok().map(_.putHeaders(`Content-Length`.unsafeFromLong(fileSize)))
          }

    }

    val server = BlazeBuilder[IO].bindHttp(port = 0).mountService(service).start.unsafeRunSync()

    val httpClient = Http1Client[Task]().runAsync.futureValue

    val client = new DefaultStorClient(
      Uri.fromString(s"http://localhost:${server.address.getPort}").getOrElse(fail()),
      BasicAuth("name", "pass"),
      httpClient
    )

    val Right(HeadResult.Exists(size)) = client.head(sha).runAsync.futureValue

    assertResult(fileSize)(size)
  }

  test("get") {
    val fileSize = 1000000
    val content = randomString(fileSize)
    val sha = content.sha256

    val service = HttpService[IO] {
      case request @ GET -> fileSha =>
        request
          .as[String]
          .flatMap { body =>
            assertResult(sha)(Sha256(fileSha.toList.head))
            assertResult(0)(body.length)

            Ok(content)
          }

    }

    val server = BlazeBuilder[IO].bindHttp(port = 0).mountService(service).start.unsafeRunSync()

    val httpClient = Http1Client[Task]().runAsync.futureValue

    val client = new DefaultStorClient(
      Uri.fromString(s"http://localhost:${server.address.getPort}").getOrElse(fail()),
      BasicAuth("name", "pass"),
      httpClient
    )

    val Right(GetResult.Downloaded(file, size)) = client.get(sha).runAsync.futureValue

    assertResult(sha.toString.toLowerCase)(file.sha256.toLowerCase)
    assertResult(fileSize)(size)
    assertResult(fileSize)(file.size)
  }

  test("post") {
    val content = randomString(1000000)
    val sha = content.sha256

    val service = HttpService[IO] {
      case request @ POST -> fileSha =>
        val Some(Authorization(Credentials.Token(AuthScheme.Basic, token))) = Authorization.from(request.headers)

        assertResult("bmFtZTpwYXNz")(token)

        request
          .as[String]
          .flatMap { body =>
            assertResult(sha)(Sha256(fileSha.toList.head))
            assertResult(sha)(body.sha256)

            Ok()
          }

    }

    val server = BlazeBuilder[IO].bindHttp(port = 0).mountService(service).start.unsafeRunSync()

    val httpClient = Http1Client[Task]().runAsync.futureValue

    val client = new DefaultStorClient(
      Uri.fromString(s"http://localhost:${server.address.getPort}").getOrElse(fail()),
      BasicAuth("name", "pass"),
      httpClient
    )

    assertResult(Right(PostResult.AlreadyExists))(client.post(sha)(content.newInputStream).runAsync.futureValue)

  }

}
