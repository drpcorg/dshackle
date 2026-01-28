package io.emeraldpay.dshackle.upstream.lowerbound

import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.config.ChainsConfig

object GoldLowerBounds {

    private lateinit var bounds: Map<Chain, Map<LowerBoundType, ChainsConfig.GoldLowerBound>>

    fun init(chainConfigs: Collection<ChainsConfig.ChainConfig>) {
        bounds = chainConfigs.filter {
            it.shortNames.isNotEmpty() &&
                Global.chainById(it.shortNames[0]) != Chain.UNSPECIFIED &&
                it.goldLowerBounds.isNotEmpty()
        }.associate { cfg ->
            Global.chainById(cfg.shortNames[0]) to cfg.goldLowerBounds.mapKeys { toLowerBoundType(it.key) }
        }
    }

    fun getBound(chain: Chain, boundType: LowerBoundType): ChainsConfig.GoldLowerBound? {
        return bounds[chain]?.get(boundType)
    }

    private fun toLowerBoundType(type: ChainsConfig.LowerBoundType): LowerBoundType {
        return LowerBoundType.byName(type.name)
    }
}
