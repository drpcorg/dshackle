package io.emeraldpay.dshackle.upstream.lowerbound

import io.emeraldpay.api.proto.BlockchainOuterClass
import java.time.Instant

data class LowerBoundData(
    val lowerBound: Long,
    val timestamp: Long,
    val type: LowerBoundType,
) : Comparable<LowerBoundData> {
    constructor(lowerBound: Long, type: LowerBoundType) : this(lowerBound, Instant.now().epochSecond, type)

    companion object {
        fun default() = LowerBoundData(0, 0, LowerBoundType.UNKNOWN)
    }

    override fun compareTo(other: LowerBoundData): Int {
        return this.lowerBound.compareTo(other.lowerBound)
    }
}

enum class LowerBoundType {
    UNKNOWN, STATE, SLOT, BLOCK, TX, LOGS, TRACE, PROOF, BLOB, EPOCH, RECEIPTS
}

fun BlockchainOuterClass.LowerBoundType.fromProtoType(): LowerBoundType {
    return when (this) {
        BlockchainOuterClass.LowerBoundType.LOWER_BOUND_SLOT -> LowerBoundType.SLOT
        BlockchainOuterClass.LowerBoundType.LOWER_BOUND_UNSPECIFIED -> LowerBoundType.UNKNOWN
        BlockchainOuterClass.LowerBoundType.LOWER_BOUND_STATE -> LowerBoundType.STATE
        BlockchainOuterClass.LowerBoundType.LOWER_BOUND_BLOCK -> LowerBoundType.BLOCK
        BlockchainOuterClass.LowerBoundType.UNRECOGNIZED -> LowerBoundType.UNKNOWN
        BlockchainOuterClass.LowerBoundType.LOWER_BOUND_TX -> LowerBoundType.TX
        BlockchainOuterClass.LowerBoundType.LOWER_BOUND_LOGS -> LowerBoundType.LOGS
        BlockchainOuterClass.LowerBoundType.LOWER_BOUND_TRACE -> LowerBoundType.TRACE
        BlockchainOuterClass.LowerBoundType.LOWER_BOUND_PROOF -> LowerBoundType.PROOF
        BlockchainOuterClass.LowerBoundType.LOWER_BOUND_BLOB -> LowerBoundType.BLOB
        BlockchainOuterClass.LowerBoundType.LOWER_BOUND_EPOCH -> LowerBoundType.EPOCH
        BlockchainOuterClass.LowerBoundType.LOWER_BOUND_RECEIPTS -> LowerBoundType.RECEIPTS
    }
}
