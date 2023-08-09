package io.emeraldpay.dshackle.config

import io.emeraldpay.dshackle.Chain
import java.time.Duration

data class ChainsConfig(private val chains: Map<Chain, RawChainConfig>, val currentDefault: RawChainConfig?) {
    companion object {
        @JvmStatic
        fun default(): ChainsConfig = ChainsConfig(emptyMap(), RawChainConfig.default())
    }

    data class RawChainConfig(
        var expectedBlockTime: Duration? = null,
        var syncingLagSize: Int? = null,
        var laggingLagSize: Int? = null,
        var callLimitContract: String? = null,
        var options: UpstreamsConfig.PartialOptions? = null
    ) {

        companion object {
            @JvmStatic
            fun default() = RawChainConfig(
                syncingLagSize = 6,
                laggingLagSize = 1
            )
        }
    }

    data class ChainConfig(
        val expectedBlockTime: Duration,
        val syncingLagSize: Int,
        val laggingLagSize: Int,
        val options: UpstreamsConfig.PartialOptions,
        val callLimitContract: String?
    ) {
        companion object {
            @JvmStatic
            fun default() = ChainConfig(Duration.ofSeconds(12), 6, 1, UpstreamsConfig.PartialOptions(), null)
        }
    }

    fun resolve(chain: Chain): ChainConfig {
        val default = currentDefault ?: panic()
        val raw = chains[chain] ?: default
        val options = default.options?.merge(raw.options) ?: raw.options ?: UpstreamsConfig.PartialOptions()

        return ChainConfig(
            laggingLagSize = raw.laggingLagSize ?: default.laggingLagSize ?: panic(),
            syncingLagSize = raw.syncingLagSize ?: default.syncingLagSize ?: panic(),
            options = options,
            callLimitContract = raw.callLimitContract,
            expectedBlockTime = raw.expectedBlockTime ?: default.expectedBlockTime ?: panic(),
        )
    }

    fun patch(patch: ChainsConfig) = ChainsConfig(
        merge(this.chains, patch.chains),
        merge(this.currentDefault!!, patch.currentDefault)
    )

    private fun merge(
        current: RawChainConfig,
        patch: RawChainConfig?
    ) = RawChainConfig(
        syncingLagSize = patch?.syncingLagSize ?: current.syncingLagSize,
        laggingLagSize = patch?.laggingLagSize ?: current.laggingLagSize,
        options = patch?.options ?: current.options,
        callLimitContract = patch?.callLimitContract ?: current.callLimitContract,
        expectedBlockTime = patch?.expectedBlockTime ?: current.expectedBlockTime
    )

    private fun merge(
        current: Map<Chain, RawChainConfig>,
        patch: Map<Chain, RawChainConfig>
    ): Map<Chain, RawChainConfig> {
        val currentMut = current.toMutableMap()

        for (k in patch) {
            currentMut.merge(k.key, k.value) { v1, v2 -> merge(v1, v2) }
        }

        return currentMut.toMap()
    }

    fun panic(): Nothing = throw IllegalStateException("Chains settings state is illegal - default config is null")
}
