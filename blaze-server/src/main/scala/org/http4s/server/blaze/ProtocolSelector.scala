package org.http4s.server.blaze

import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import javax.net.ssl.SSLEngine

import org.http4s.blaze.channel.SocketConnection
import org.http4s.{Request, AttributeEntry, AttributeMap}
import org.http4s.blaze.http.http20._
import org.http4s.blaze.http.http20.NodeMsg.Http2Msg
import org.http4s.blaze.pipeline.{TailStage, LeafBuilder}
import org.http4s.server.HttpService


import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration


/** Facilitates the use of ALPN when using blaze http2 support */
object ProtocolSelector {
  def apply(engine: SSLEngine, service: HttpService,
            maxHeaderLen: Int, conn: Option[SocketConnection], es: ExecutorService): ALPNSelector = {

    def preference(protos: Seq[String]): String = {
      protos.find {
        case "h2" | "h2-14" | "h2-15" => true
        case _                        => false
      }.getOrElse("http1.1")
    }

    def select(s: String): LeafBuilder[ByteBuffer] = s match {
      case "h2" | "h2-14" | "h2-15" => LeafBuilder(http2Stage(service, maxHeaderLen, conn, es))
      case _                        => LeafBuilder(new Http1ServerStage(service, conn, es))
    }

    new ALPNSelector(engine, preference, select)
  }

  private def http2Stage(service: HttpService, maxHeadersLength: Int,
                         conn: Option[SocketConnection], es: ExecutorService): TailStage[ByteBuffer] = {

    // Make the objects that will be used for the whole connection
    val ec = ExecutionContext.fromExecutorService(es)
    val ra = for {
      conn <- conn
      raddr <- conn.remoteInetAddress
    } yield AttributeMap(AttributeEntry(Request.Keys.Remote, raddr))

    def newNode(streamId: Int): LeafBuilder[Http2Msg] = {
      LeafBuilder(new Http2NodeStage(streamId, Duration.Inf, ec, ra.getOrElse(AttributeMap.empty), service))
    }

    new Http2Stage(
      maxHeadersLength,
      node_builder = newNode,
      timeout = Duration.Inf,
      maxInboundStreams = 300,
      ec = ec
    )
  }
}