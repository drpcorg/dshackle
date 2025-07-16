package io.emeraldpay.dshackle.upstream.ethereum

import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundDetector

abstract class EthereumLowerBoundDetectorBase(chain: Chain) : LowerBoundDetector(chain) {

    companion object {
        val commonErrorPatterns = setOf(
            Regex(".*seekInFiles\\(invIndex=unknown index,txNum=\\d+\\) but data before txNum=\\d+ not available.*"),
        )
    }
}
