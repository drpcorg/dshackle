package io.emeraldpay.dshackle.upstream.lowerbound

import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.config.ChainsConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GoldLowerBoundsTest {

    @Test
    fun `init with all bounds`() {
        val cfg = ChainsConfig.ChainConfig.default()
            .copy(
                shortNames = listOf("polygon"),
                goldLowerBounds = ChainsConfig.LowerBoundType.entries
                    .associateWith {
                        if (it == ChainsConfig.LowerBoundType.TX || it == ChainsConfig.LowerBoundType.RECEIPTS) {
                            ChainsConfig.GoldLowerBoundWithHash(5L, "hash_$it")
                        } else {
                            ChainsConfig.GoldLowerBound(10L)
                        }
                    },
            )

        GoldLowerBounds.init(listOf(cfg))

        LowerBoundType.entries
            .filter { it != LowerBoundType.UNKNOWN }
            .forEach {
                val bound = GoldLowerBounds.getBound(Chain.POLYGON__MAINNET, it)

                assertThat(bound).isNotNull

                if (it == LowerBoundType.TX || it == LowerBoundType.RECEIPTS) {
                    assertThat(bound)
                        .usingRecursiveComparison()
                        .isEqualTo(ChainsConfig.GoldLowerBoundWithHash(5L, "hash_$it"))
                } else {
                    assertThat(bound)
                        .usingRecursiveComparison()
                        .isEqualTo(ChainsConfig.GoldLowerBound(10L))
                }
            }
    }

    @Test
    fun `no gold bound`() {
        val cfg = ChainsConfig.ChainConfig.default()
            .copy(
                shortNames = listOf("polygon"),
            )

        GoldLowerBounds.init(listOf(cfg))

        LowerBoundType.entries
            .filter { it != LowerBoundType.UNKNOWN }
            .forEach {
                val bound = GoldLowerBounds.getBound(Chain.POLYGON__MAINNET, it)

                assertThat(bound).isNull()
            }
    }
}
