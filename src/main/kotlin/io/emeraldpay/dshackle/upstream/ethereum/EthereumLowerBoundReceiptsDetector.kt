package io.emeraldpay.dshackle.upstream.ethereum

import io.emeraldpay.dshackle.Defaults
import io.emeraldpay.dshackle.data.BlockContainer
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundData
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundDetector
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundType
import io.emeraldpay.dshackle.upstream.lowerbound.detector.RecursiveLowerBound
import io.emeraldpay.dshackle.upstream.lowerbound.toHex
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import reactor.core.publisher.Flux

class EthereumLowerBoundReceiptsDetector(
    private val upstream: Upstream,
) : LowerBoundDetector(upstream.getChain()) {

    companion object {
        const val MAX_OFFSET = 20
        private const val NO_RECEIPTS_DATA = "No receipts data"

        private val NO_RECEIPTS_ERRORS = setOf(
            NO_RECEIPTS_DATA,
            "block not found with number",
            "requested epoch was a null round",
            "missing trie node",
            "old data not available due to pruning",
            "Unexpected error", // hyperliquid
        ).plus(EthereumLowerBoundBlockDetector.NO_BLOCK_ERRORS)
    }

    private val recursiveLowerBound = RecursiveLowerBound(upstream, LowerBoundType.RECEIPTS, NO_RECEIPTS_ERRORS, lowerBounds)

    override fun period(): Long {
        return 3
    }

    override fun internalDetectLowerBound(): Flux<LowerBoundData> {
        return recursiveLowerBound.recursiveDetectLowerBoundWithOffset(MAX_OFFSET) { block ->
            upstream.getIngressReader()
                .read(
                    ChainRequest("eth_getBlockByNumber", ListParams(block.toHex(), false)),
                )
                .timeout(Defaults.internalCallsTimeout)
                .doOnNext {
                    if (it.hasResult() && it.getResult().contentEquals("null".toByteArray())) {
                        throw IllegalStateException(NO_RECEIPTS_DATA)
                    }
                }
                .handle { it, sink ->
                    val blockJson = BlockContainer.fromEthereumJson(it.getResult(), upstream.getId())
                    if (blockJson.transactions.isEmpty()) {
                        sink.error(IllegalStateException(NO_RECEIPTS_DATA))
                        return@handle
                    }
                    sink.next(blockJson.transactions[0].toHexWithPrefix())
                }
                .flatMap { tx ->
                    upstream.getIngressReader()
                        .read(
                            ChainRequest("eth_getTransactionReceipt", ListParams(tx)),
                        )
                        .timeout(Defaults.internalCallsTimeout)
                        .doOnNext {
                            if (it.hasResult() && it.getResult().contentEquals("null".toByteArray())) {
                                throw IllegalStateException(NO_RECEIPTS_DATA)
                            }
                        }
                }
        }
    }

    override fun types(): Set<LowerBoundType> {
        return setOf(LowerBoundType.RECEIPTS)
    }
}
