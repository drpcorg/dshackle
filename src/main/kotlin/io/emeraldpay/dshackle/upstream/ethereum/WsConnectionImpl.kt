/**
 * Copyright (c) 2021 EmeraldPay, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.emeraldpay.dshackle.upstream.ethereum

import io.emeraldpay.dshackle.Defaults
import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.config.AuthConfig
import io.emeraldpay.dshackle.upstream.ethereum.WsConnection.ConnectionState.CONNECTED
import io.emeraldpay.dshackle.upstream.ethereum.WsConnection.ConnectionState.DISCONNECTED
import io.emeraldpay.dshackle.upstream.rpcclient.JsonRpcError
import io.emeraldpay.dshackle.upstream.rpcclient.JsonRpcException
import io.emeraldpay.dshackle.upstream.rpcclient.JsonRpcRequest
import io.emeraldpay.dshackle.upstream.rpcclient.JsonRpcResponse
import io.emeraldpay.dshackle.upstream.rpcclient.JsonRpcWsMessage
import io.emeraldpay.dshackle.upstream.rpcclient.ResponseWSParser
import io.emeraldpay.dshackle.upstream.rpcclient.RpcMetrics
import io.emeraldpay.etherjar.rpc.RpcResponseError
import io.micrometer.core.instrument.Metrics
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufInputStream
import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.resolver.DefaultAddressResolverGroup
import org.reactivestreams.Publisher
import org.slf4j.LoggerFactory
import org.springframework.util.backoff.BackOff
import org.springframework.util.backoff.BackOffExecution
import org.springframework.util.backoff.ExponentialBackOff
import org.springframework.util.backoff.FixedBackOff
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Scheduler
import reactor.netty.http.client.HttpClient
import reactor.netty.http.client.WebsocketClientSpec
import reactor.netty.http.websocket.WebsocketInbound
import reactor.netty.http.websocket.WebsocketOutbound
import reactor.util.function.Tuples
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

open class WsConnectionImpl(
    private val uri: URI,
    private val origin: URI,
    private val basicAuth: AuthConfig.ClientBasicAuth?,
    private val rpcMetrics: RpcMetrics?,
    private val scheduler: Scheduler,
) : AutoCloseable, WsConnection, Cloneable {

    companion object {
        private val log = LoggerFactory.getLogger(WsConnectionImpl::class.java)

        private const val IDS_START = 100

        // WebSocket Frame limit.
        // Default is 65_536, but Geth responds with larger frames,
        // and connection gets dropped with:
        // > io.netty.handler.codec.http.websocketx.CorruptedWebSocketFrameException: Max frame length of 65536 has been exceeded
        // It's unclear what is the right limit here, but 5mb seems to be working (1mb isn't always working)
        private const val DEFAULT_FRAME_SIZE = 5 * 1024 * 1024
        private const val RESET_BACKOFF_TIMEOUT = 10 * 1000

        // The max size from multiple frames that may represent a single message
        // Accept up to 15Mb messages, because Geth is using 15mb, though it's not clear what should be a right value
        private const val DEFAULT_MSG_SIZE = 15 * 1024 * 1024
    }

    var frameSize: Int = DEFAULT_FRAME_SIZE
    var msgSizeLimit: Int = DEFAULT_MSG_SIZE

    private var reconnectBackoff: BackOff = ExponentialBackOff().also {
        it.initialInterval = Duration.ofMillis(100).toMillis()
        it.maxInterval = Duration.ofMinutes(1).toMillis()
    }
    private var currentBackOff = reconnectBackoff.start()

    private val parser = ResponseWSParser()

    private val resetBackoffExecutor = Executors.newScheduledThreadPool(2)
    private var resetBackoffTask: ScheduledFuture<Unit>? = null

    private val subscriptionResponses = Sinks
        .many()
        .multicast()
        .directBestEffort<JsonRpcWsMessage>()

    private var rpcSend = Sinks
        .many()
        .unicast()
        .onBackpressureBuffer<JsonRpcRequest>()

    private val disconnects = Sinks
        .many()
        .multicast()
        .directBestEffort<Instant>()

    private val connectionInfo = Sinks
        .many()
        .multicast()
        .directBestEffort<WsConnection.ConnectionInfo>()

    private val currentRequests = ConcurrentHashMap<Int, Sinks.One<JsonRpcResponse>>()

    private val connId = UUID.randomUUID().toString()
    private val sendIdSeq = AtomicInteger(IDS_START)
    private val sendExecutor = Executors.newSingleThreadExecutor()
    private var keepConnection = true
    private var firstConnect = true
    private var connection: Disposable? = null
    private val reconnecting = AtomicBoolean(false)

    override val isConnected: Boolean
        get() = connection != null && !reconnecting.get()

    override fun connectionId(): String = connId

    fun setReconnectIntervalSeconds(value: Long) {
        reconnectBackoff = FixedBackOff(value * 1000, FixedBackOff.UNLIMITED_ATTEMPTS)
        currentBackOff = reconnectBackoff.start()
    }

    override fun connect() {
        keepConnection = true
        connectInternal()
    }

    override fun connectionInfoFlux(): Flux<WsConnection.ConnectionInfo> =
        connectionInfo.asFlux()
            .distinctUntilChanged { it.connectionState }

    private fun tryReconnectLater() {
        if (!keepConnection) {
            return
        }
        val alreadyReconnecting = reconnecting.getAndSet(true)
        if (alreadyReconnecting) {
            return
        }
        connectionInfo.tryEmitNext(WsConnection.ConnectionInfo(connId, DISCONNECTED))
        if (firstConnect) {
            firstConnect = false
        }

        // rpcSend is already CANCELLED, since the subscription owned by the previous connection is gone
        // so we need to create a new Sink. Emit Complete is probably useless, and just in case
        rpcSend.tryEmitComplete()
        rpcSend = Sinks
            .many()
            .unicast()
            .onBackpressureBuffer<JsonRpcRequest>()
        resetBackoffTask?.cancel(false)
        val retryInterval = currentBackOff.nextBackOff()
        resetBackoffTask = resetBackoffExecutor.schedule<Unit>({
            currentBackOff = reconnectBackoff.start()
        }, RESET_BACKOFF_TIMEOUT + retryInterval, TimeUnit.MILLISECONDS,)
        if (retryInterval == BackOffExecution.STOP) {
            log.warn("Reconnect backoff exhausted. Permanently closing the connection")
            return
        }
        log.info("Reconnect to $uri in ${retryInterval}ms...")
        Global.control.schedule(
            {
                reconnecting.set(false)
                connectInternal()
            },
            retryInterval,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun connectInternal() {
        log.info("Connecting to WebSocket: $uri")
        connection?.dispose()
        connection = HttpClient.create()
            .resolver(DefaultAddressResolverGroup.INSTANCE)
            .doOnDisconnected {
                disconnects.tryEmitNext(Instant.now())
                log.info("Disconnected from $uri")
                if (keepConnection) {
                    tryReconnectLater()
                }
            }
            .doOnConnected {
                if (!firstConnect) {
                    connectionInfo.tryEmitNext(WsConnection.ConnectionInfo(connId, CONNECTED))
                } else {
                    firstConnect = false
                }
            }
            .doOnError(
                { _, t ->
                    log.warn("Failed to connect to $uri. Error: ${t.message}")
                    // going to try to reconnect later
                    tryReconnectLater()
                },
                { _, t ->
                    log.warn("Failed to process response from $uri. Error: ${t.message}")
                },
            )
            .headers { headers ->
                headers.add(HttpHeaderNames.ORIGIN, origin)
                basicAuth?.let { auth ->
                    val tmp: String = auth.username + ":" + auth.password
                    val base64password = Base64.getEncoder().encodeToString(tmp.toByteArray())
                    headers.add(HttpHeaderNames.AUTHORIZATION, "Basic $base64password")
                }
            }
            .let {
                if (uri.scheme == "wss") it.secure() else it
            }
            .websocket(
                WebsocketClientSpec.builder()
                    .handlePing(true)
                    .compress(false)
                    .maxFramePayloadLength(frameSize)
                    .build(),
            )
            .uri(uri)
            .handle { inbound, outbound ->
                handle(inbound, outbound)
            }
            .onErrorResume { t ->
                log.debug("Dropping WS connection to $uri. Error: ${t.message}")
                Mono.empty<Void>()
            }
            .subscribe()
    }

    fun handle(inbound: WebsocketInbound, outbound: WebsocketOutbound): Publisher<Void> {
        var read = false
        val consumer = inbound
            .aggregateFrames(msgSizeLimit)
            .receiveFrames()
            .map { ByteBufInputStream(it.content()).readAllBytes() }
            .filter { it.isNotEmpty() }
            .flatMap {
                try {
                    val msg = parser.parse(it)
                    if (!read) {
                        if (msg.error != null) {
                            log.warn("Received error ${msg.error.code} from $uri: ${msg.error.message}")
                        } else {
                            // restart backoff only after a successful read from the connection,
                            // otherwise it may restart it even if the connection is faulty or responds only with error
                            currentBackOff = reconnectBackoff.start()
                            read = true
                        }
                    }
                    onMessage(msg)
                } catch (t: Throwable) {
                    log.warn("Failed to process WS message", t)
                    Mono.empty()
                }
            }
            .onErrorResume { t ->
                log.warn("Connection dropped to $uri. Error: ${t.message}", t)
                // going to try to reconnect later
                tryReconnectLater()
                // completes current outbound flow
                Mono.empty()
            }

        val calls = rpcSend
            .asFlux()
            .map {
                Unpooled.wrappedBuffer(it.toJson())
            }

        return outbound.send(
            Flux.merge(
                calls.subscribeOn(scheduler),
                consumer.then(Mono.empty<ByteBuf>()).subscribeOn(scheduler),
            ),
        )
    }

    private fun onMessage(msg: ResponseWSParser.WsResponse): Mono<Void> {
        return Mono.fromCallable {
            when (msg.type) {
                ResponseWSParser.Type.RPC -> onMessageRpc(msg)
                ResponseWSParser.Type.SUBSCRIPTION -> onMessageSubscription(msg)
            }
        }.then()
    }

    private fun onMessageRpc(msg: ResponseWSParser.WsResponse) {
        val rpcResponse = JsonRpcResponse(
            msg.value,
            msg.error,
            msg.id,
            null,
        )
        val sender = currentRequests.remove(msg.id.asNumber().toInt())
        if (sender == null) {
            log.warn("Unknown response received for ${msg.id} with body ${msg.value?.let { String(it) }}")
        } else {
            try {
                val emitResult = sender.tryEmitValue(rpcResponse)
                if (emitResult.isFailure) {
                    log.debug("Response is ignored $emitResult")
                }
            } catch (t: Throwable) {
                log.warn("Response is not processed ${t.message}", t)
            }
        }
    }

    private fun onMessageSubscription(msg: ResponseWSParser.WsResponse) {
        val subscription = JsonRpcWsMessage(
            msg.value,
            msg.error,
            msg.id.asString(),
        )
        val status = subscriptionResponses.tryEmitNext(subscription)
        if (status.isFailure) {
            if (status == Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
                log.debug("No subscribers to WS response - ${msg.value?.let { String(it) }}")
            } else {
                log.warn("Failed to proceed with a WS message: $status")
            }
        }
    }

    override fun getSubscribeResponses(): Flux<JsonRpcWsMessage> {
        return Flux.from(subscriptionResponses.asFlux())
    }

    override fun callRpc(originalRequest: JsonRpcRequest): Mono<JsonRpcResponse> {
        return Mono.fromCallable {
            val startTime = System.nanoTime()
            // use an internal id sequence, to avoid id conflicts with user calls
            val internalId = sendIdSeq.getAndIncrement()
            val originalId = originalRequest.id
            Tuples.of(originalRequest.copy(id = internalId), originalId, startTime)
        }.flatMap { request ->
            waitForResponse(request.t1, request.t2, request.t3)
        }
    }

    private fun sendRpc(request: JsonRpcRequest) {
        // submit to upstream in a separate thread, to free current thread (needs for subscription, etc)
        sendExecutor.execute {
            val result = rpcSend.tryEmitNext(request)
            if (result.isFailure) {
                log.warn("Failed to send RPC request: $result")
            }
        }
    }

    private fun waitForResponse(request: JsonRpcRequest, originalId: Int, startTime: Long): Mono<JsonRpcResponse> {
        val internalId = request.id.toLong()
        val onResponse = Sinks.one<JsonRpcResponse>()
        currentRequests[internalId.toInt()] = onResponse
        val noResponse = JsonRpcException(
            JsonRpcResponse.Id.from(originalId),
            JsonRpcError(
                RpcResponseError.CODE_INTERNAL_ERROR,
                "Response not received from WebSocket",
            ),
            null,
            false,
        )

        val failOnDisconnect = Mono.from(disconnects.asFlux())
            .flatMap {
                Mono.error<JsonRpcResponse>(
                    JsonRpcException(
                        JsonRpcResponse.Id.from(originalId),
                        JsonRpcError(
                            RpcResponseError.CODE_UPSTREAM_CONNECTION_ERROR,
                            "Disconnected from WebSocket",
                        ),
                    ),
                )
            }

        return Mono.from(onResponse.asMono()).or(failOnDisconnect)
            .doOnSubscribe { sendRpc(request) }
            .take(Defaults.timeout)
            .doOnNext { rpcMetrics?.timer?.record(System.nanoTime() - startTime, TimeUnit.NANOSECONDS) }
            .doOnError { rpcMetrics?.fails?.increment() }
            .map { it.copyWithId(JsonRpcResponse.Id.from(originalId)) }
            .switchIfEmpty(
                Mono.fromCallable { log.warn("No response for ${request.method} ${request.params}") }.then(Mono.error(noResponse)),
            )
            .doFinally { currentRequests.remove(internalId.toInt()) }
    }

    override fun close() {
        log.info("Closing connection to WebSocket $uri")
        connectionInfo.tryEmitComplete()
        keepConnection = false
        connection?.dispose()
        connection = null
        currentRequests.clear()
        rpcMetrics?.fails?.let {
            it.close()
            Metrics.globalRegistry.remove(it)
        }
        rpcMetrics?.timer?.let {
            it.close()
            Metrics.globalRegistry.remove(it)
        }
        resetBackoffExecutor.shutdown()
    }
}
