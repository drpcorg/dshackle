package io.emeraldpay.dshackle.upstream

import io.emeraldpay.api.proto.BlockchainOuterClass
import io.emeraldpay.dshackle.config.IndexConfig

class LogsOracle(
    var config: IndexConfig.Index,
) {
    val db = org.drpc.logsoracle.LogsOracle(config.store, config.ram_limit ?: 0L)

    fun estimate(request: BlockchainOuterClass.EstimateLogsCountRequest): BlockchainOuterClass.EstimateLogsCountResponse {
        val count = db.query(
            request.fromBlock,
            request.toBlock,
            request.getAddressesList(),
            listOf(
                request.getTopics1List(),
                request.getTopics2List(),
                request.getTopics3List(),
                request.getTopics4List(),
            ),
        )

        return BlockchainOuterClass.EstimateLogsCountResponse.newBuilder()
            .setSucceed(true)
            .setCount(count)
            .build()
    }
}
