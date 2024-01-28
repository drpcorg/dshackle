package io.emeraldpay.dshackle.upstream.ethereum

import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.upstream.RecursiveLowerBoundBlockDetector
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.rpcclient.JsonRpcRequest
import io.emeraldpay.dshackle.upstream.rpcclient.JsonRpcResponse
import io.emeraldpay.dshackle.upstream.toHex
import reactor.core.publisher.Mono

class EthereumLowerBoundBlockDetector(
    chain: Chain,
    private val upstream: Upstream,
) : RecursiveLowerBoundBlockDetector(chain, upstream) {

    companion object {
        private val nonRetryableErrors = setOf(
            "No state available for block", // nethermind
            "missing trie node", // geth
            "header not found", // optimism, bsc, avalanche
            "Node state is pruned", // kava
            "is not available, lowest height is", // kava, cronos
            "State already discarded for", // moonriver, moonbeam
            "your node is running with state pruning", // fuse
            "Max height of block allowed", // mumbai
            "failed to compute tipset state", // filecoin-calibration
            "bad tipset height", // filecoin-calibration
            "body not found for block",
            "request beyond head block",
            "block not found",
            "could not find block",
            "unknown block",
            "header for hash not found",
            "after last accepted block",
            "Version has either been pruned, or is for a future block", // cronos
            "no historical RPC is available for this historical", // optimism
            "historical backend error", // optimism
            "load state tree: failed to load state tree", // filecoin
        )
    }

    override fun hasState(blockNumber: Long): Mono<Boolean> {
        return upstream.getIngressReader().read(
            JsonRpcRequest(
                "eth_getBalance",
                listOf("0x756F45E3FA69347A9A973A725E3C98bC4db0b5a0", blockNumber.toHex()),
            ),
        )
            .retryWhen(retrySpec(nonRetryableErrors))
            .flatMap(JsonRpcResponse::requireResult)
            .map { true }
            .onErrorReturn(false)
    }
}
