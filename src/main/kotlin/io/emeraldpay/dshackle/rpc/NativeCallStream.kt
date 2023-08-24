package io.emeraldpay.dshackle.rpc

import com.google.protobuf.ByteString
import io.emeraldpay.api.proto.BlockchainOuterClass.NativeCallReplyItem
import io.emeraldpay.api.proto.BlockchainOuterClass.NativeCallRequest
import io.emeraldpay.dshackle.config.StreamingConfig
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import kotlin.math.min

@Service
class NativeCallStream(
    private val nativeCall: NativeCall,
    streamingConfig: StreamingConfig
) {
    private val chunkSize = streamingConfig.chunkSize

    fun nativeCall(
        requestMono: Mono<NativeCallRequest>
    ): Flux<NativeCallReplyItem> {
        return nativeCall.nativeCall(requestMono)
            .concatMap {
                if (it.payload.size() <= chunkSize || !it.succeed) {
                    Mono.just(it)
                } else {
                    Flux.fromIterable(chunks(it))
                }
            }
    }

    private fun chunks(response: NativeCallReplyItem): List<NativeCallReplyItem> {
        val chunks = mutableListOf<ByteArray>()
        val responseBytes = response.payload.toByteArray()

        for (i in responseBytes.indices step+chunkSize) {
            chunks.add(responseBytes.copyOfRange(i, min(i + chunkSize, responseBytes.size)))
        }

        return chunks
            .mapIndexed { index, bytes ->
                NativeCallReplyItem.newBuilder()
                    .apply {
                        id = response.id
                        payload = ByteString.copyFrom(bytes)
                        succeed = true
                        signature = response.signature
                        upstreamId = response.upstreamId
                        chunked = true
                        finalChunk = index == chunks.size - 1
                    }.build()
            }
    }
}
