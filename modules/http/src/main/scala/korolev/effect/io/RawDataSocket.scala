package korolev.effect.io

import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousChannelGroup, AsynchronousSocketChannel, CompletionHandler}
import korolev.data.{Bytes, BytesLike}
import korolev.data.syntax._
import korolev.effect.{Effect, Stream}

sealed class RawDataSocket[F[_]: Effect, B: BytesLike](channel: AsynchronousSocketChannel,
                                                       buffer: ByteBuffer,
                                                       label: String) extends DataSocket[F, B] {

  private var inProgress = false
  private var canceled = false

  val stream: Stream[F, B] = new Stream[F, B] {
    def pull(): F[Option[B]] = Effect[F].promise { cb =>
      if (canceled) {
        cb(Right(None))
      } else {
        buffer.clear()
        if (inProgress) {
          println(s"${Console.RED}Concurrent pull() detected in RawDataSocket($label, ${channel.getRemoteAddress}) ${Console.RESET}")
          Thread
            .currentThread()
            .getStackTrace
            .foreach { ste =>
              println(s"${Console.RED}  $ste${Console.RESET}")
            }
        }
        inProgress = true
        channel.read(buffer, (), new CompletionHandler[Integer, Unit] {
          def completed(bytesRead: Integer, notUsed: Unit): Unit = {
            inProgress = false
            if (bytesRead < 0) {
              // Socket was closed
              cb(Right(None))
            } else {
              // TODO copyFromBuffer
              val array = BytesLike[B].copyFromArray(buffer.array()).slice(0, bytesRead.toLong)
              cb(Right(Some(array)))
            }
          }

          def failed(throwable: Throwable, notUsed: Unit): Unit = {
            cb(Left(throwable))
          }
        })
      }
    }

    def cancel(): F[Unit] =
      Effect[F].delay {
        if (!canceled) {
          canceled = true
          channel.close()
        }
      }
  }

  def read(buffer: ByteBuffer): F[Int] = Effect[F].promise[Int] { cb =>
    val handler = new CompletionHandler[Integer, Unit] {
      def completed(result: Integer, attachment: Unit): Unit = cb(Right(result))
      def failed(exc: Throwable, attachment: Unit): Unit = cb(Left(exc))
    }
    channel.read(buffer, (), handler)
  }

  def write(buffer: ByteBuffer): F[Unit] = {
    Effect[F].promise { cb =>
      val handler = new CompletionHandler[Integer, Unit] {
        def completed(bytesWritten: Integer, notUsed: Unit): Unit =
          if (buffer.hasRemaining) channel.write(buffer, (), this)
          else cb(Right(()))
        def failed(throwable: Throwable, notUsed: Unit): Unit =
          cb(Left(throwable))
      }
      channel.write(buffer, (), handler)
    }
  }

  def write(bytes: B): F[Unit] = {
    val buffer = bytes.asBuffer // TODO Maybe it should be static allocated buffer
    write(buffer)
  }
}

object RawDataSocket {

  def connect[F[_]: Effect, B: BytesLike](address: SocketAddress,
                                          buffer: ByteBuffer = ByteBuffer.allocate(8096),
                                          group: AsynchronousChannelGroup = null): F[RawDataSocket[F, B]] =
    Effect[F].promise { cb =>
      val channel = AsynchronousSocketChannel.open(group)
      lazy val ds = new RawDataSocket[F, B](channel, buffer, "outgoing connection")
      channel.connect(address, (), completionHandler[Void](cb.compose(_.map(_ => ds))))
    }
}
