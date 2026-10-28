package io.emeraldpay.dshackle.upstream.ethereum

import io.emeraldpay.dshackle.Defaults
import io.emeraldpay.dshackle.config.ChainsConfig
import io.emeraldpay.dshackle.data.BlockContainer
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.lowerbound.GoldLowerBounds
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundData
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundType
import io.emeraldpay.dshackle.upstream.lowerbound.detector.RecursiveLowerBound
import io.emeraldpay.dshackle.upstream.lowerbound.toHex
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Detects the receipts bound and reports it as the logs bound too: eth_getLogs reads receipts,
 * so a node answers it only where it has them (block headers alone say nothing about logs).
 */
class EthereumLowerBoundReceiptsDetector(
    private val upstream: Upstream,
) : EthereumLowerBoundDetectorBase(upstream.getChain()) {

    companion object {
        const val MAX_OFFSET = 20
        private const val NO_RECEIPTS_DATA = "No receipts data"
        private const val NO_TRANSACTIONS = "No transactions in block"

        private val NO_RECEIPTS_ERRORS = setOf(
            NO_RECEIPTS_DATA,
            NO_TRANSACTIONS,
            "block not found with number",
            "requested epoch was a null round",
            "missing trie node",
            "old data not available due to pruning",
            "Unexpected error", // hyperliquid
            "invalid block height", // hyperliquid
            "header not found",
            "pruned history unavailable", // xlayer
            "transaction indexing is in progress",
            "has been pruned; earliest available is",
        ).plus(EthereumLowerBoundBlockDetector.NO_BLOCK_ERRORS)
    }

    private val recursiveLowerBound = RecursiveLowerBound(upstream, LowerBoundType.RECEIPTS, NO_RECEIPTS_ERRORS, lowerBounds, commonErrorPatterns)

    override fun period(): Long {
        return 3
    }

    override fun internalDetectLowerBound(): Flux<LowerBoundData> {
        return detectReceiptsLowerBound()
            .flatMap {
                Flux.just(it, lowerBoundFrom(it, LowerBoundType.LOGS))
            }
    }

    override fun types(): Set<LowerBoundType> {
        return setOf(LowerBoundType.RECEIPTS, LowerBoundType.LOGS)
    }

    private fun detectReceiptsLowerBound(): Flux<LowerBoundData> {
        val receiptsGoldBound = GoldLowerBounds.getBound(upstream.getChain(), LowerBoundType.RECEIPTS)
        if (receiptsGoldBound == null || receiptsGoldBound !is ChainsConfig.GoldLowerBoundWithHash) {
            return contiguousDetectReceiptsLowerBound()
        }
        return Mono.just(receiptsGoldBound)
            .flatMapMany { bound ->
                upstream.getIngressReader()
                    .read(ChainRequest("eth_getTransactionReceipt", ListParams(bound.hash)))
                    .timeout(Defaults.internalCallsTimeout)
                    .flatMap(ChainResponse::requireResult)
                    .flatMapMany {
                        if (it.contentEquals("null".toByteArray())) {
                            throw IllegalStateException("no gold bound")
                        } else {
                            Flux.just(LowerBoundData(1, LowerBoundType.RECEIPTS))
                        }
                    }
                    .onErrorResume {
                        contiguousDetectReceiptsLowerBound()
                    }
            }
    }

    private fun contiguousDetectReceiptsLowerBound(): Flux<LowerBoundData> {
        return recursiveLowerBound.detectContiguousLowerBound(MAX_OFFSET, NO_TRANSACTIONS) { block ->
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
                        sink.error(IllegalStateException(NO_TRANSACTIONS))
                        return@handle
                    }
                    // the last transaction: on OP-stack the first one is the L1-info deposit
                    sink.next(blockJson.transactions.last().toHexWithPrefix())
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
}
