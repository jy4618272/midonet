/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.cluster.services.state.client

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.{ExecutionContext, Future, Promise, blocking}

import com.google.protobuf.{Message, MessageLite}
import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.protobuf._
import io.netty.handler.logging.LoggingHandler

import rx.Observer

class TestServer(decoder: MessageLite) extends Observer[Message] {

    import TestServer._

    var attachedObserver: Observer[Message] = null
    val state = new AtomicReference(Inactive: State)

    private val log = Logger(LoggerFactory.getLogger(classOf[TestServer]))

    val boosGroup = new NioEventLoopGroup()
    val workerGroup = new NioEventLoopGroup()

    val serverFuture = new ServerBootstrap()
        .group(boosGroup, workerGroup)
        .channel(classOf[NioServerSocketChannel])
        .childHandler(new ChannelInitializer[io.netty.channel.Channel] {
            override def initChannel(ch: Channel) = {
                initPipeline(ch.pipeline())
            }
        })
        .bind(0)

    assert(serverFuture.sync().isSuccess)

    val serverChannel = serverFuture.channel match {
        case c: NioServerSocketChannel => c
    }

    val port = serverChannel.localAddress().getPort
    log.info(s"Listening on port $port")

    private def initPipeline(pipeline: ChannelPipeline) = {
        pipeline.addLast("frameDecoder", new ProtobufVarint32FrameDecoder)
            .addLast("protobufDecoder", new ProtobufDecoder(decoder))
            .addLast("frameEncoder", new ProtobufVarint32LengthFieldPrepender)
            .addLast("protobufEncoder", new ProtobufEncoder())
            .addLast(new LoggingHandler())
            .addLast(new ClientHandler(this,register,unregister))
    }

    private def register(c: Channel): Unit = {
        if(!state.compareAndSet(Inactive,Client(c))) {
            throw new Exception("Only one concurrent client supported")
        }
    }

    private def unregister(c: Channel): Unit = {
        state.get match {
            case cur @ Client(_) => assert(state.compareAndSet(cur,Inactive))
            case _ =>
        }
    }

    def write(msg: Message): Unit = {
        state.get match {
            case Client(channel) => channel.writeAndFlush(msg)
            case Inactive => throw new IllegalStateException(
                "Write when disconnected")
        }
    }


    def close(): Unit = {
        state.get match {
            case Client(channel) => channel.close()
            case Inactive => throw new IllegalStateException(
                "Close when disconnected")
        }
    }

    def hasClient: Boolean = state.get match {
        case Client(_) => true
        case _ => false
    }

    override def onCompleted(): Unit = {
        if (attachedObserver != null) attachedObserver.onCompleted()
    }

    override def onError(cause: Throwable) = {
        if (attachedObserver != null) attachedObserver.onError(cause)
    }

    override def onNext(msg: Message): Unit = {
        if (attachedObserver != null) attachedObserver.onNext(msg)
    }
}

object TestServer {
    sealed trait State
    case object Inactive extends State
    case class Client(channel: Channel) extends State

    private class ClientHandler(observer: Observer[Message],
                        register: Channel => Unit,
                        unregister: Channel => Unit)
        extends SimpleChannelInboundHandler[Message] {

        var completed: Boolean = false

        private val log = Logger(LoggerFactory.getLogger(classOf[ClientHandler]))
        private var channel: Channel = null

        override def channelRead0(ctx: ChannelHandlerContext, msg: Message): Unit = {
            log.info(s"onNext: $msg")
            observer.onNext(msg)
        }

        override def channelInactive(ctx: ChannelHandlerContext): Unit = {
            log.info("closed")
            if (!completed) {
                completed = true
                log.info(s"onCompleted")
                unregister(ctx.channel())
                observer.onCompleted()
            }
        }

        override def channelActive(ctx: ChannelHandlerContext): Unit = {
            log.info(s"onActive")
            channel = ctx.channel()
            register(channel)
        }

        override def exceptionCaught(ctx: ChannelHandlerContext,
                                     cause: Throwable): Unit = {
            log.info(s"exception: $cause")
            if (!completed) {
                log.info(s"onError: $cause")
                completed = true
                unregister(ctx.channel())
                observer.onError(cause)
            }
        }
    }
}