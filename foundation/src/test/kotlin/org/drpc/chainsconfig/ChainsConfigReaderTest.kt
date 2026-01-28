package org.drpc.chainsconfig

import io.emeraldpay.dshackle.config.ChainsConfig
import io.emeraldpay.dshackle.config.ChainsConfigReader
import io.emeraldpay.dshackle.foundation.ChainOptionsReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigInteger

class ChainsConfigReaderTest {

    @Test
    fun `read standard config without custom`() {
        val reader = ChainsConfigReader(ChainOptionsReader())
        val config = reader.read(null)
        val arb = config.resolve("sepolia")
        assertEquals(arb.chainId, "0xaa36a7")
        assertEquals(arb.expectedBlockTime.seconds, 12L)
        assertEquals(arb.options.validatePeers, null)
        assertEquals(arb.id, "Sepolia")

        val ethc = config.resolve("ethereum-classic")
        assertEquals(ethc.chainId, "0x3d")
        assertEquals(ethc.netVersion, BigInteger.valueOf(1))
        assertEquals(ethc.code, "ETC")
        assertEquals(ethc.grpcId, 101)
        assertEquals(ethc.expectedBlockTime.seconds, 12)
        assertEquals(ethc.laggingLagSize, 1)
        assertEquals(ethc.syncingLagSize, 6)
        assertEquals(ethc.id, "mainnet")
    }

    @Test
    fun `read standard config with custom one`() {
        val reader = ChainsConfigReader(ChainOptionsReader())
        val chains = reader.read(this.javaClass.classLoader.getResourceAsStream("configs/chains-basic.yaml")!!)!!
        val config = chains.resolve("fantom")
        assertEquals(config.expectedBlockTime.seconds, 10)
        assertEquals(config.chainId, "0xfb")
        assertEquals(config.syncingLagSize, 11)
        assertEquals(config.laggingLagSize, 4)
        assertEquals(config.code, "FTA")
        assertEquals(config.grpcId, 102)
        assertEquals(config.netVersion, BigInteger.valueOf(251))
        assertEquals(15L, config.goldLowerBounds[ChainsConfig.LowerBoundType.STATE]!!.block)
        assertEquals(150L, config.goldLowerBounds[ChainsConfig.LowerBoundType.BLOCK]!!.block)
        assertEquals(1L, config.goldLowerBounds[ChainsConfig.LowerBoundType.TX]!!.block)
        assertEquals(
            "0x5e77a04531c7c107af1882d76cbff9486d0a9aa53701c30888509d4f5f2b003a",
            (config.goldLowerBounds[ChainsConfig.LowerBoundType.TX] as ChainsConfig.GoldLowerBoundWithHash).hash,
        )
        assertEquals(1000L, config.goldLowerBounds[ChainsConfig.LowerBoundType.RECEIPTS]!!.block)
        assertEquals(
            "0x4e72a04531c7c107af1882d76cbff9486d0a9aa53701c30888509d4f5f2b003a",
            (config.goldLowerBounds[ChainsConfig.LowerBoundType.RECEIPTS] as ChainsConfig.GoldLowerBoundWithHash).hash,
        )
    }
}
