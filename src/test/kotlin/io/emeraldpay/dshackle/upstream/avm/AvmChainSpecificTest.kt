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

val avmBlockHeader = """
    {
        "block": {
            "rnd": 30000000,
            "ts": 1696802363,
            "prev": "blk-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "seed": "c29tZXNlZWRieXRlc3RoYXRpczMyYnl0ZXNsb25nISEh",
            "txn": "dHhucm9vdGhhc2h2YWx1ZWZvcnRlc3RpbmcxMjM0NTY=",
            "gen": "mainnet-v1.0",
            "proto": "https://github.com/algorandfoundation/specs/tree/somehash"
        },
        "cert": {}
    }
""".trimIndent()

class AvmChainSpecificTest {

    @Test
    fun parseBlockChainsThroughBlockEndpoint() {
        val reader = object : ChainReader {
            override fun read(key: ChainRequest): Mono<ChainResponse> {
                Assertions.assertThat(key.method).isEqualTo("GET#/v2/blocks/30000000")
                return Mono.just(ChainResponse(avmBlockHeader.toByteArray(), null))
            }
        }

        val result = AvmChainSpecific.parseBlock(
            avmStatusSynced.toByteArray(),
            "upstream-1",
            reader,
        ).block()!!

        Assertions.assertThat(result.height).isEqualTo(30000000L)
        Assertions.assertThat(result.upstreamId).isEqualTo("upstream-1")
        Assertions.assertThat(result.timestamp.epochSecond).isEqualTo(1696802363L)
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
