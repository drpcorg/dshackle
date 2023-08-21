package io.emeraldpay.dshackle.reader

import io.emeraldpay.dshackle.commons.SPAN_ERROR
import io.emeraldpay.dshackle.commons.SPAN_READER_NAME
import io.emeraldpay.dshackle.commons.SPAN_READER_RESULT
import io.emeraldpay.dshackle.commons.SPAN_REQUEST_CANCELLED
import io.emeraldpay.dshackle.commons.SPAN_REQUEST_INFO
import io.emeraldpay.dshackle.commons.SPAN_STATUS_MESSAGE
import io.emeraldpay.dshackle.data.HashId
import io.emeraldpay.dshackle.upstream.rpcclient.JsonRpcRequest
import org.springframework.cloud.sleuth.Tracer
import org.springframework.cloud.sleuth.instrument.reactor.ReactorSleuth
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.switchIfEmpty

class SpannedReader<K, D>(
    private val reader: Reader<K, D>,
    private val tracer: Tracer,
    private val name: String,
    private val additionalParams: Map<String, String> = emptyMap()
) : Reader<K, D> {

    override fun read(key: K): Mono<D> {
        val newSpan = tracer.nextSpan(tracer.currentSpan())
            .name(reader.javaClass.name)
            .tag(SPAN_READER_NAME, name)
            .start()

        extractInfoFromKey(key)?.let {
            newSpan.tag(SPAN_REQUEST_INFO, it)
        }
        additionalParams.forEach { newSpan.tag(it.key, it.value) }

        return reader.read(key)
            .contextWrite { ReactorSleuth.putSpanInScope(tracer, it, newSpan) }
            .doOnError {
                newSpan.tag(SPAN_ERROR, "true")
                    .tag(SPAN_STATUS_MESSAGE, it.message)
                    .end()
            }
            .doOnNext { newSpan.end() }
            .doOnCancel {
                newSpan.tag(SPAN_STATUS_MESSAGE, SPAN_REQUEST_CANCELLED).end()
            }
            .switchIfEmpty {
                newSpan.tag(SPAN_READER_RESULT, "empty result").end()
                Mono.empty()
            }
    }

    private fun extractInfoFromKey(key: K): String? {
        return when (key) {
            is JsonRpcRequest -> "method: ${key.method}"
            is HashId, Long -> "params: $key"
            else -> null
        }
    }
}
