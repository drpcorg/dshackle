package io.emeraldpay.dshackle.upstream.aztec

import io.emeraldpay.dshackle.reader.ChainReader
import io.emeraldpay.dshackle.upstream.ChainCallError
import io.emeraldpay.dshackle.upstream.ChainCallUpstreamException
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ChainResponse
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicInteger

// v5 (v5.0.0-rc.1) node_getChainTips response: proposed stays flat, the rest nested.
private val chainTipsResponse = """
    {
        "proposed": {"number": 12345, "hash": "0xaaaa"},
        "checkpointed": {"block": {"number": 12340, "hash": "0xbbbb"}, "checkpoint": {"number": 100, "hash": "0x1111"}},
        "proven": {"block": {"number": 12330, "hash": "0xcccc"}, "checkpoint": {"number": 99, "hash": "0x2222"}},
        "finalized": {"block": {"number": 12320, "hash": "0xdddd"}, "checkpoint": {"number": 98, "hash": "0x3333"}}
    }
""".trimIndent()

// legacy node_getL2Tips response (v3-era flat proposed)
private val l2TipsResponse = """
    {
        "proposed": {"number": 999, "hash": "0x0999"},
        "proven": {"number": 990, "hash": "0x0990"},
        "checkpointed": {"number": 980, "hash": "0x0980"}
    }
""".trimIndent()

// defensive: some versions nest proposed under .block
private val nestedProposedResponse = """
    {
        "proposed": {"block": {"number": 777, "hash": "0x0777"}}
    }
""".trimIndent()

private fun methodNotFound(method: String) =
    Mono.error<ChainResponse>(
        ChainCallUpstreamException(
            ChainResponse.NumberId(1),
            ChainCallError(-32601, "Method not found: $method"),
        ),
    )

class AztecChainSpecificTest {

    @Test
    fun latestBlockRequestUsesL2Tips() {
        Assertions.assertThat(AztecChainSpecific.latestBlockRequest().method)
            .isEqualTo("node_getL2Tips")
    }

    @Test
    fun parseBlockReadsFlatProposed() {
        val result = AztecChainSpecific.parseBlock(
            chainTipsResponse.toByteArray(),
            "up-flat",
            noopReader(),
        ).block()!!

        Assertions.assertThat(result.height).isEqualTo(12345L)
        Assertions.assertThat(result.hash.toHex()).contains("aaaa")
    }

    @Test
    fun parseBlockReadsNestedProposed() {
        val result = AztecChainSpecific.parseBlock(
            nestedProposedResponse.toByteArray(),
            "up-nested",
            noopReader(),
        ).block()!!

        Assertions.assertThat(result.height).isEqualTo(777L)
        Assertions.assertThat(result.hash.toHex()).contains("0777")
    }

    @Test
    fun getLatestBlockUsesL2TipsWhenAvailable() {
        val calls = mutableListOf<String>()
        val reader = object : ChainReader {
            override fun read(key: ChainRequest): Mono<ChainResponse> {
                calls += key.method
                return Mono.just(ChainResponse(l2TipsResponse.toByteArray(), null))
            }
        }

        val result = AztecChainSpecific.getLatestBlock(reader, "up-legacy").block()!!

        Assertions.assertThat(result.height).isEqualTo(999L)
        Assertions.assertThat(calls).containsExactly("node_getL2Tips")
    }

    @Test
    fun getLatestBlockFallsBackToChainTipsAndCaches() {
        val calls = mutableListOf<String>()
        val reader = object : ChainReader {
            override fun read(key: ChainRequest): Mono<ChainResponse> {
                calls += key.method
                return when (key.method) {
                    "node_getL2Tips" -> methodNotFound("node_getL2Tips")
                    "node_getChainTips" -> Mono.just(ChainResponse(chainTipsResponse.toByteArray(), null))
                    else -> Mono.error(IllegalStateException("unexpected ${key.method}"))
                }
            }
        }

        // first poll: probes legacy, falls back to v5
        val first = AztecChainSpecific.getLatestBlock(reader, "up-v5").block()!!
        Assertions.assertThat(first.height).isEqualTo(12345L)
        Assertions.assertThat(calls).containsExactly("node_getL2Tips", "node_getChainTips")

        // second poll: must hit the cached working method directly, no dead probe
        calls.clear()
        val second = AztecChainSpecific.getLatestBlock(reader, "up-v5").block()!!
        Assertions.assertThat(second.height).isEqualTo(12345L)
        Assertions.assertThat(calls).containsExactly("node_getChainTips")
    }

    @Test
    fun getLatestBlockDoesNotFallBackOnOtherErrors() {
        val attempts = AtomicInteger(0)
        val reader = object : ChainReader {
            override fun read(key: ChainRequest): Mono<ChainResponse> {
                attempts.incrementAndGet()
                return Mono.error(
                    ChainCallUpstreamException(
                        ChainResponse.NumberId(1),
                        ChainCallError(-32000, "internal error"),
                    ),
                )
            }
        }

        val thrown = runCatching { AztecChainSpecific.getLatestBlock(reader, "up-err").block() }
        Assertions.assertThat(thrown.isFailure).isTrue()
        // only the primary method is attempted; no fallback probe on a non-method-not-found error
        Assertions.assertThat(attempts.get()).isEqualTo(1)
    }

    private fun noopReader() = object : ChainReader {
        override fun read(key: ChainRequest): Mono<ChainResponse> =
            Mono.error(IllegalStateException("not expected"))
    }
}
