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
    ;

    companion object {
        fun fromBlockRef(v: String): FinalizationType {
            return when (v) {
                "safe" -> SAFE_BLOCK
                "finalized" -> FINALIZED_BLOCK
                else -> UNKNOWN
            }
        }
    }

    fun toProtoFinalizationType(): BlockchainOuterClass.FinalizationType {
        return when (this) {
            FINALIZED_BLOCK -> BlockchainOuterClass.FinalizationType.FINALIZATION_FINALIZED_BLOCK
            UNKNOWN -> BlockchainOuterClass.FinalizationType.UNRECOGNIZED
            SAFE_BLOCK -> BlockchainOuterClass.FinalizationType.FINALIZATION_SAFE_BLOCK
        }
    }

    fun toBlockRef(): String{
        return when (this) {
            FINALIZED_BLOCK -> "finalized"
            SAFE_BLOCK -> "safe"
            UNKNOWN -> "unknown"
        }
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
