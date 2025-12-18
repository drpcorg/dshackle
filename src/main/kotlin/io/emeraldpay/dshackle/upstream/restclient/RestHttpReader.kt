package io.emeraldpay.dshackle.upstream.restclient

import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.config.AuthConfig
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.HttpReader
import io.emeraldpay.dshackle.upstream.RequestMetrics
import io.emeraldpay.dshackle.upstream.generic.ChainSpecificRegistry
import io.emeraldpay.dshackle.upstream.rpcclient.ResponseRpcParser
import io.emeraldpay.dshackle.upstream.rpcclient.RestParams
import io.emeraldpay.dshackle.upstream.stream.AggregateResponse
import io.emeraldpay.dshackle.upstream.stream.Chunk
import io.emeraldpay.dshackle.upstream.stream.Response
import io.emeraldpay.dshackle.upstream.stream.StreamResponse
import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.HttpMethod
import org.apache.commons.lang3.time.StopWatch
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.kotlin.core.publisher.switchIfEmpty
import reactor.netty.http.client.HttpClientResponse
import java.util.concurrent.TimeUnit

class RestHttpReader(
    target: String,
    maxConnections: Int,
    queueSize: Int,
    metrics: RequestMetrics,
    private val httpScheduler: Scheduler,
    private val chain: Chain,
    basicAuth: AuthConfig.ClientBasicAuth? = null,
    tlsCAAuth: ByteArray? = null,
    customHeaders: Map<String, String> = emptyMap(),
) : HttpReader(target, maxConnections, queueSize, metrics, basicAuth, tlsCAAuth, customHeaders) {

    private val parser = ResponseRpcParser()
    private val requestParser = RestRequestParser
    private val headersToForward = ChainSpecificRegistry.resolve(chain).getResponseHeadersToForward()

    private fun extractResponseHeaders(header: HttpClientResponse): Map<String, String> {
        return headersToForward
            .mapNotNull { name -> header.responseHeaders().get(name)?.let { name to it } }
            .toMap()
    }

    override fun internalRead(key: ChainRequest): Mono<ChainResponse> {
        val startTime = StopWatch()
        return Mono.just(key)
            .doOnNext {
                if (!startTime.isStarted) {
                    startTime.start()
                }
            }
            .flatMap(this::execute)
            .doOnNext {
                if (startTime.isStarted) {
                    metrics?.timer?.record(startTime.nanoTime, TimeUnit.NANOSECONDS)
                }
            }
            .handle { it, sink ->
                when (it) {
                    is StreamResponse -> sink.next(ChainResponse(it.stream, key.id, it.headers))
                    is AggregateResponse -> {
                        if (it.code != 200) {
                            val error = parser.readError(Global.objectMapper.createParser(it.response))
                            sink.next(ChainResponse(null, error, it.headers))
                        } else {
                            sink.next(ChainResponse(it.response, null, it.headers))
                        }
                    }
                    else -> sink.error(IllegalStateException("Wrong response type"))
                }
            }
    }

    private fun execute(key: ChainRequest): Mono<out Response> {
        val restParams = key.params as RestParams

        val methodParams = key.method.split("#")
        val restMethod = methodParams[0]
        val path = methodParams[1]

        val url = target
            .plus(requestParser.transformPathParams(path, restParams.pathParams))
            .plus(requestParser.transformQueryParams(restParams.queryParams))

        val response = httpClient.headers { headers ->
            restParams.headers.forEach {
                headers.add(it.first, it.second)
            }
        }
            .request(HttpMethod.valueOf(restMethod))
            .uri(url)
            .send(Mono.just(Unpooled.wrappedBuffer(key.toJson())))

        return if (!key.isStreamed) {
            response.response { header, bytes ->
                val statusCode = header.status().code()
                val responseHeaders = extractResponseHeaders(header)

                bytes.aggregate().asByteArray().publishOn(httpScheduler).map {
                    AggregateResponse(it, statusCode, responseHeaders)
                }.switchIfEmpty {
                    Mono.just(AggregateResponse(ByteArray(0), statusCode, responseHeaders))
                }
            }.single()
        } else {
            response.responseConnection { t, u ->
                val responseHeaders = extractResponseHeaders(t)

                if (t.status().code() != 200) {
                    u.inbound().receive().aggregate().asByteArray().publishOn(httpScheduler)
                        .map { AggregateResponse(it, t.status().code(), responseHeaders) }
                } else {
                    Mono.just(
                        StreamResponse(
                            Flux.concat(
                                u.inbound().receive().asByteArray().publishOn(httpScheduler)
                                    .map { Chunk(it, false) },
                                Mono.just(Chunk(ByteArray(0), true)),
                            ),
                            responseHeaders,
                        ),
                    )
                }
            }.single()
        }
    }
}
