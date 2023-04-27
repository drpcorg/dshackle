/**
 * Copyright (c) 2020 EmeraldPay, Inc
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
package io.emeraldpay.dshackle.quorum

import io.emeraldpay.dshackle.commons.API_READER
import io.emeraldpay.dshackle.commons.SPAN_REQUEST_API_TYPE
import io.emeraldpay.dshackle.commons.SPAN_REQUEST_UPSTREAM_ID
import io.emeraldpay.dshackle.reader.Reader
import io.emeraldpay.dshackle.reader.SpannedReader
import io.emeraldpay.dshackle.upstream.ApiSource
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.rpcclient.JsonRpcError
import io.emeraldpay.dshackle.upstream.rpcclient.JsonRpcException
import io.emeraldpay.dshackle.upstream.rpcclient.JsonRpcRequest
import io.emeraldpay.dshackle.upstream.rpcclient.JsonRpcResponse
import io.emeraldpay.dshackle.upstream.signature.ResponseSigner
import io.emeraldpay.etherjar.rpc.RpcException
import org.slf4j.LoggerFactory
import org.springframework.cloud.sleuth.Tracer
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.util.function.Tuple3
import reactor.util.function.Tuple4
import reactor.util.function.Tuples
import java.util.Optional
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BiFunction
import java.util.function.Function

/**
 * Makes request with applying Quorum
 */
class QuorumRpcReader(
    private val apiControl: ApiSource,
    private val quorum: CallQuorum,
    private val signer: ResponseSigner?,
    private val tracer: Tracer
) : Reader<JsonRpcRequest, QuorumRpcReader.Result> {

    companion object {
        private val log = LoggerFactory.getLogger(QuorumRpcReader::class.java)
    }

    constructor(apiControl: ApiSource, quorum: CallQuorum, tracer: Tracer) : this(apiControl, quorum, null, tracer)

    override fun read(key: JsonRpcRequest): Mono<Result> {
        // needs at least one response, so start a request
        apiControl.request(1)

        // uses a mix of retry strategy and managed Publisher for calls.
        // retry is used when an error happened
        // but if no error received, we check quorum and if not enough data received we request more
        // eventually source of upstreams is Completed (or something Errored) and if finalizes the result

        val retrySpec = reactor.util.retry.Retry.from { signal ->
            signal.takeUntil {
                it.totalRetries() >= 3 || quorum.isResolved() || quorum.isFailed()
            }.doOnNext {
                // when retried it needs one more API source
                apiControl.request(1)
            }
        }

        val defaultResult: Mono<Result> = setupDefaultResult(key)

        return Flux.from(apiControl)
            .transform(execute(key, retrySpec))
            .next()
            // if last call resulted in error it's still possible that request was resolved correctly. ex. for BroadcastQuorum
            .onErrorResume { err ->
                if (quorum.isResolved()) {
                    Mono.just(quorum)
                } else {
                    Mono.error(err)
                }
            }
            .doOnNext {
                if (!it.isResolved() && !it.isFailed()) {
                    log.debug("No quorum for ${key.method} using [$quorum]. Error: ${it.getError()?.message ?: ""}")
                }
            }
            .transform(processResult(defaultResult))
    }

    fun execute(key: JsonRpcRequest, retrySpec: reactor.util.retry.Retry): Function<Flux<Upstream>, Mono<CallQuorum>> {
        val quorumReduce = BiFunction<CallQuorum, Tuple4<ByteArray, Optional<ResponseSigner.Signature>, Upstream, Optional<String>>, CallQuorum> { res, a ->
            if (res.record(a.t1, a.t2.orElse(null), a.t3, a.t4.orElse(null))) {
                log.trace("Quorum is resolved for method ${key.method}")
                apiControl.resolve()
            } else {
                log.trace("Quorum needs more responses for method ${key.method}")
                // quorum needs more responses, so ask api controller to make another
                apiControl.request(1)
            }
            res
        }
        return Function { apiFlux ->
            apiFlux
                .takeUntil {
                    quorum.isFailed() || quorum.isResolved()
                }
                .flatMap { api ->
                    log.trace("Calling upstream ${api.getId()} with method ${key.method}")
                    callApi(api, key)
                }
                .retryWhen(retrySpec)
                // record all correct responses until quorum reached
                .reduce(quorum, quorumReduce)
        }
    }

    fun processResult(defaultResult: Mono<Result>): Function<Mono<CallQuorum>, Mono<Result>> {
        return Function { quorumResult ->
            quorumResult
                .filter { it.isResolved() } // return nothing if not resolved
                .map { quorum ->
                    // TODO find actual quorum number
                    Result(quorum.getResult()!!, quorum.getSignature(), 1, quorum.getResolvedBy(), quorum.getProvidedUpstreamId())
                }
                .switchIfEmpty(defaultResult)
        }
    }

    fun callApi(api: Upstream, key: JsonRpcRequest): Mono<Tuple4<ByteArray, Optional<ResponseSigner.Signature>, Upstream, Optional<String>>> {
        val apiReader = api.getIngressReader()
        val spanParams = mapOf(
            SPAN_REQUEST_API_TYPE to apiReader.javaClass.name,
            SPAN_REQUEST_UPSTREAM_ID to api.getId()
        )
        return SpannedReader(apiReader, tracer, API_READER, spanParams)
            .read(key)
            .flatMap { response ->
                log.trace("Received response from upstream ${api.getId()} for method ${key.method}")
                response.requireResult()
                    .transform(withSignatureAndUpstream(api, key, response))
            }
            // must catch not only the processing of a response but also errors thrown from the .read() call
            .transform(withErrorResume(api, key))
            .map { Tuples.of(it.t1, it.t2, api, it.t3) }
    }

    fun withSignatureAndUpstream(api: Upstream, key: JsonRpcRequest, response: JsonRpcResponse): Function<Mono<ByteArray>, Mono<Tuple3<ByteArray, Optional<ResponseSigner.Signature>, Optional<String>>>> {
        return Function { src ->
            src.map {
                val signature = response.providedSignature
                    ?: if (key.nonce != null) {
                        signer?.sign(key.nonce, response.getResult(), api.getId())
                    } else {
                        null
                    }
                Tuples.of(it, Optional.ofNullable(signature), Optional.ofNullable(response.providedUpstreamId))
            }
        }
    }

    fun <T> withErrorResume(api: Upstream, key: JsonRpcRequest): Function<Mono<T>, Mono<T>> {
        return Function { src ->
            src.onErrorResume { err ->
                log.error("Error during call upstream ${api.getId()} with method ${key.method}", err)
                // when the call failed with an error we want to notify the quorum because
                // it may use the error message or other details
                //
                val cleanErr: JsonRpcException = when (err) {
                    is RpcException -> JsonRpcException.from(err)
                    is JsonRpcException -> err
                    else -> JsonRpcException(
                        JsonRpcResponse.NumberId(key.id),
                        JsonRpcError(-32603, "Unhandled internal error: ${err.javaClass}: ${err.message}")
                    )
                }
                quorum.record(cleanErr, null, api,)
                // if it's failed after that, then we don't need more calls, stop api source
                if (quorum.isFailed()) {
                    log.debug("Quorum is failed, stop api source. Upstream ${api.getId()}, method ${key.method}")
                    apiControl.resolve()
                } else {
                    log.debug("Received an error, trying to request next upstream")
                    apiControl.request(1)
                }
                Mono.empty()
            }
        }
    }

    fun setupDefaultResult(key: JsonRpcRequest): Mono<Result> {
        return Mono.just(quorum).flatMap { q ->
            if (q.isFailed()) {
                val err = q.getError()?.asException(JsonRpcResponse.NumberId(key.id))
                    ?: JsonRpcException(JsonRpcResponse.NumberId(key.id), JsonRpcError(-32603, "Unhandled Upstream error"))
                log.warn("Quorum is failed. Method ${key.method}, message ${err.message}")
                Mono.error<Result>(err)
            } else {
                log.warn("Did not get any result from upstream. Method [${key.method}] using [$q]")
                Mono.empty<Result>()
            }
        }
    }

    fun getValidAttemptsCount(): AtomicInteger =
        apiControl.attempts()

    class Result(
        val value: ByteArray,
        val signature: ResponseSigner.Signature?,
        val quorum: Int,
        val resolvers: Collection<Upstream>,
        val providedUpstreamId: String?
    )
}
