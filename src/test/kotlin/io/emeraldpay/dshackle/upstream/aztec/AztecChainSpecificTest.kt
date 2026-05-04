package io.emeraldpay.dshackle.upstream.aztec

import io.emeraldpay.dshackle.data.BlockId
import io.emeraldpay.dshackle.reader.ChainReader
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.UpstreamAvailability
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

private val l2TipsOk = """
    {
      "proposed":     {"number": 21360, "hash": "0x4242"},
      "proven":       {"number": 21331, "hash": "0x1111"},
      "checkpointed": {"number": 21358, "hash": "0x2222"}
    }
""".trimIndent()

private val l2TipsZeroProposed = """
    {
      "proposed":     {"number": 0,      "hash": ""},
      "proven":       {"number": 0,      "hash": ""},
      "checkpointed": {"number": 0,      "hash": ""}
    }
""".trimIndent()

private val l2TipsMissingProposed = """
    {
      "proven":       {"number": 100, "hash": "0xabc"},
      "checkpointed": {"number": 90,  "hash": "0xdef"}
    }
""".trimIndent()

private val l2TipsMissingProven = """
    {
      "proposed":     {"number": 100, "hash": "0xabc"},
      "checkpointed": {"number": 90,  "hash": "0xdef"}
    }
""".trimIndent()

private val l2TipsProvenAhead = """
    {
      "proposed":     {"number": 100, "hash": "0xabc"},
      "proven":       {"number": 200, "hash": "0xdef"},
      "checkpointed": {"number": 90,  "hash": "0x111"}
    }
""".trimIndent()

private val l2TipsExcessiveLag = """
    {
      "proposed":     {"number": 1000, "hash": "0xabc"},
      "proven":       {"number": 100,  "hash": "0xdef"},
      "checkpointed": {"number": 95,   "hash": "0x111"}
    }
""".trimIndent()

private val emptyReader = object : ChainReader {
    override fun read(key: ChainRequest): Mono<ChainResponse> = Mono.empty()
}

class AztecChainSpecificTest {

    @Test
    fun `parseBlock extracts proposed tip`() {
        val result = AztecChainSpecific.parseBlock(l2TipsOk.toByteArray(), "up-1", emptyReader).block()!!

        Assertions.assertThat(result.height).isEqualTo(21360)
        Assertions.assertThat(result.hash).isEqualTo(BlockId.from("0x4242"))
        Assertions.assertThat(result.upstreamId).isEqualTo("up-1")
        Assertions.assertThat(result.parentHash).isNull()
    }

    @Test
    fun `validateTips OK on healthy tips`() {
        Assertions.assertThat(AztecChainSpecific.validateTips(l2TipsOk.toByteArray(), 5, "up-1"))
            .isEqualTo(UpstreamAvailability.OK)
    }

    @Test
    fun `validateTips OK when lagging is zero (lag check disabled)`() {
        // gap is 1000-100=900 but lagging=0 disables the check
        Assertions.assertThat(AztecChainSpecific.validateTips(l2TipsExcessiveLag.toByteArray(), 0, "up-1"))
            .isEqualTo(UpstreamAvailability.OK)
    }

    @Test
    fun `validateTips SYNCING on empty data`() {
        Assertions.assertThat(AztecChainSpecific.validateTips(ByteArray(0), 5, "up-1"))
            .isEqualTo(UpstreamAvailability.SYNCING)
    }

    @Test
    fun `validateTips SYNCING on whitespace only`() {
        Assertions.assertThat(AztecChainSpecific.validateTips("   \n\t  ".toByteArray(), 5, "up-1"))
            .isEqualTo(UpstreamAvailability.SYNCING)
    }

    @Test
    fun `validateTips SYNCING on unparseable data`() {
        Assertions.assertThat(AztecChainSpecific.validateTips("{ not json".toByteArray(), 5, "up-1"))
            .isEqualTo(UpstreamAvailability.SYNCING)
    }

    @Test
    fun `validateTips SYNCING on JSON null`() {
        Assertions.assertThat(AztecChainSpecific.validateTips("null".toByteArray(), 5, "up-1"))
            .isEqualTo(UpstreamAvailability.SYNCING)
    }

    @Test
    fun `validateTips SYNCING when proposed is zero`() {
        Assertions.assertThat(AztecChainSpecific.validateTips(l2TipsZeroProposed.toByteArray(), 5, "up-1"))
            .isEqualTo(UpstreamAvailability.SYNCING)
    }

    @Test
    fun `validateTips SYNCING when proposed is missing`() {
        Assertions.assertThat(AztecChainSpecific.validateTips(l2TipsMissingProposed.toByteArray(), 5, "up-1"))
            .isEqualTo(UpstreamAvailability.SYNCING)
    }

    @Test
    fun `validateTips SYNCING when proven is missing`() {
        Assertions.assertThat(AztecChainSpecific.validateTips(l2TipsMissingProven.toByteArray(), 5, "up-1"))
            .isEqualTo(UpstreamAvailability.SYNCING)
    }

    @Test
    fun `validateTips SYNCING when proven is ahead of proposed`() {
        Assertions.assertThat(AztecChainSpecific.validateTips(l2TipsProvenAhead.toByteArray(), 5, "up-1"))
            .isEqualTo(UpstreamAvailability.SYNCING)
    }

    @Test
    fun `validateTips SYNCING on excessive prover lag`() {
        // lagging=5 → threshold=50, gap=900 > 50 → SYNCING
        Assertions.assertThat(AztecChainSpecific.validateTips(l2TipsExcessiveLag.toByteArray(), 5, "up-1"))
            .isEqualTo(UpstreamAvailability.SYNCING)
    }
}
