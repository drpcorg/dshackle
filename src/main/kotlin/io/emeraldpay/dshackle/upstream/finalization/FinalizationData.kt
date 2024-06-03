package io.emeraldpay.dshackle.upstream.finalization

import io.emeraldpay.api.proto.BlockchainOuterClass

class FinalizationData(
    val height: Long,
    val type: FinalizationType,
)

enum class FinalizationType {
    UNKNOWN,
    SAFE_BLOCK,
    FINALIZED_BLOCK,
}

fun FinalizationType.toProtoFinalizationType(): BlockchainOuterClass.FinalizationType {
    return when (this) {
        FinalizationType.FINALIZED_BLOCK -> BlockchainOuterClass.FinalizationType.FINALIZATION_FINALIZED_BLOCK
        FinalizationType.UNKNOWN -> BlockchainOuterClass.FinalizationType.UNRECOGNIZED
        FinalizationType.SAFE_BLOCK -> BlockchainOuterClass.FinalizationType.FINALIZATION_SAFE_BLOCK
    }
}

fun BlockchainOuterClass.FinalizationType.fromProtoType(): FinalizationType {
    return when (this) {
        BlockchainOuterClass.FinalizationType.FINALIZATION_UNSPECIFIED -> FinalizationType.UNKNOWN
        BlockchainOuterClass.FinalizationType.FINALIZATION_SAFE_BLOCK -> FinalizationType.SAFE_BLOCK
        BlockchainOuterClass.FinalizationType.FINALIZATION_FINALIZED_BLOCK -> FinalizationType.FINALIZED_BLOCK
        BlockchainOuterClass.FinalizationType.UNRECOGNIZED -> FinalizationType.UNKNOWN
    }
}
