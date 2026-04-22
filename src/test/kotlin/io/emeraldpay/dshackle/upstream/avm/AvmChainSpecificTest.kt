package io.emeraldpay.dshackle.upstream.avm

import io.emeraldpay.dshackle.reader.ChainReader
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.UpstreamAvailability
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

val avmStatusSynced = """
    {
        "last-round": 30000000,
        "last-version": "https://github.com/algorandfoundation/specs/tree/somehash",
        "next-version": "https://github.com/algorandfoundation/specs/tree/somehash",
        "next-version-round": 30000001,
        "next-version-supported": true,
        "time-since-last-round": 1500000000,
        "catchup-time": 0,
        "last-catchpoint": ""
    }
""".trimIndent()

val avmStatusCatchingUp = """
    {
        "last-round": 30000000,
        "last-version": "https://github.com/algorandfoundation/specs/tree/somehash",
        "next-version": "https://github.com/algorandfoundation/specs/tree/somehash",
        "next-version-round": 30000001,
        "next-version-supported": true,
        "time-since-last-round": 1500000000,
        "catchup-time": 1500000000,
        "last-catchpoint": "30000000#QWERTYU"
    }
""".trimIndent()

class AvmChainSpecificTest {

    @Test
    fun parseStatusAsBlock() {
        val result = AvmChainSpecific.parseBlock(
            avmStatusSynced.toByteArray(),
            "upstream-1",
            object : ChainReader {
                override fun read(key: ChainRequest): Mono<ChainResponse> = Mono.empty()
            },
        ).block()!!

        Assertions.assertThat(result.height).isEqualTo(30000000L)
        Assertions.assertThat(result.upstreamId).isEqualTo("upstream-1")
        Assertions.assertThat(result.hash.toHex()).isNotEmpty()
        Assertions.assertThat(result.parentHash?.toHex()).isNotEmpty()
    }

    @Test
    fun validateSyncedNode() {
        Assertions.assertThat(AvmChainSpecific.validate(avmStatusSynced.toByteArray(), "test"))
            .isEqualTo(UpstreamAvailability.OK)
    }

    @Test
    fun validateCatchingUpNode() {
        Assertions.assertThat(AvmChainSpecific.validate(avmStatusCatchingUp.toByteArray(), "test"))
            .isEqualTo(UpstreamAvailability.SYNCING)
    }

    @Test
    fun latestBlockRequestUsesStatusEndpoint() {
        val request = AvmChainSpecific.latestBlockRequest()
        Assertions.assertThat(request.method).isEqualTo("GET#/v2/status")
    }
}
