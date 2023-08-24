package io.emeraldpay.dshackle.rpc

import com.fasterxml.jackson.databind.JsonNode
import com.google.protobuf.ByteString
import io.emeraldpay.api.proto.BlockchainOuterClass
import io.emeraldpay.api.proto.BlockchainOuterClass.NativeCallRequest
import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.config.StreamingConfig
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.springframework.util.ResourceUtils
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration

class NativeCallStreamTest {
    private val upstreamId = "upstreamId"
    private val mapper = Global.objectMapper

    @Test
    fun `streaming response is equal to the original response`() {
        val nativeCallMock = Mockito.mock(NativeCall::class.java)
        val nativeCallStream = NativeCallStream(
            nativeCallMock, StreamingConfig(1000)
        )
        val responseFile = ResourceUtils.getFile("classpath:responses/get-by-number-response.json")
        val response = mapper.writeValueAsBytes(mapper.readValue(responseFile, JsonNode::class.java))
        val nativeCallResponse = BlockchainOuterClass.NativeCallReplyItem.newBuilder()
            .setId(1)
            .setSucceed(true)
            .setUpstreamId(upstreamId)
            .setPayload(ByteString.copyFrom(response))
            .build()
        val req = Mono.empty<NativeCallRequest>()

        `when`(nativeCallMock.nativeCall(req)).thenReturn(Flux.just(nativeCallResponse))

        val result = nativeCallStream.nativeCall(req)
            .collectList()
            .block()!!
            .map { it.payload.toByteArray() }
            .reduce { acc, bytes -> acc.plus(bytes) }

        assertTrue(response.contentEquals(result))
    }

    @Test
    fun `streaming responses is correct`() {
        val nativeCallMock = Mockito.mock(NativeCall::class.java)
        val nativeCallStream = NativeCallStream(
            nativeCallMock, StreamingConfig(5)
        )
        val response = "\"0x1126938\"".toByteArray()
        val nativeCallResponse = BlockchainOuterClass.NativeCallReplyItem.newBuilder()
            .setId(15)
            .setSucceed(true)
            .setUpstreamId(upstreamId)
            .setPayload(ByteString.copyFrom(response))
            .build()
        val req = Mono.empty<NativeCallRequest>()

        `when`(nativeCallMock.nativeCall(req)).thenReturn(Flux.just(nativeCallResponse))

        val result = nativeCallStream.nativeCall(req)

        StepVerifier.create(result)
            .expectNext(
                nativeCallReplyItemBuilder(15)
                    .setPayload(ByteString.copyFrom("\"0x11".toByteArray()))
                    .build()
            )
            .expectNext(
                nativeCallReplyItemBuilder(15)
                    .setPayload(ByteString.copyFrom("26938".toByteArray()))
                    .build()
            )
            .expectNext(
                nativeCallReplyItemBuilder(15)
                    .setFinalChunk(true)
                    .setPayload(ByteString.copyFrom("\"".toByteArray()))
                    .build()
            )
            .expectComplete()
            .verify(Duration.ofSeconds(3))
    }

    @Test
    fun `no streaming if response is too small`() {
        val nativeCallMock = Mockito.mock(NativeCall::class.java)
        val nativeCallStream = NativeCallStream(
            nativeCallMock, StreamingConfig(5)
        )
        val response = "\"0x1\"".toByteArray()
        val nativeCallResponse = BlockchainOuterClass.NativeCallReplyItem.newBuilder()
            .setId(15)
            .setSucceed(true)
            .setUpstreamId(upstreamId)
            .setPayload(ByteString.copyFrom(response))
            .build()
        val req = Mono.empty<NativeCallRequest>()

        `when`(nativeCallMock.nativeCall(req)).thenReturn(Flux.just(nativeCallResponse))

        val result = nativeCallStream.nativeCall(req)

        StepVerifier.create(result)
            .expectNext(
                nativeCallResponse
            )
            .expectComplete()
            .verify(Duration.ofSeconds(3))
    }

    private fun nativeCallReplyItemBuilder(id: Int): BlockchainOuterClass.NativeCallReplyItem.Builder {
        return BlockchainOuterClass.NativeCallReplyItem.newBuilder()
            .setId(id)
            .setChunked(true)
            .setSucceed(true)
            .setSignature(BlockchainOuterClass.NativeCallReplySignature.newBuilder().build())
            .setUpstreamId(upstreamId)
    }
}
