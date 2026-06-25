package io.emeraldpay.dshackle.upstream.calls

import io.emeraldpay.dshackle.quorum.AlwaysQuorum
import io.emeraldpay.dshackle.quorum.BroadcastQuorum
import io.emeraldpay.dshackle.quorum.CallQuorum
import io.emeraldpay.dshackle.upstream.ethereum.rpc.RpcException

class DefaultAztecMethods : CallMethods {

    private val broadcast = setOf(
        "node_sendTx",
    )

    private val allowedMethods: Set<String> = setOf(
        // Block / tip
        "node_getBlockNumber",
        "node_getProvenBlockNumber",
        "node_getL2Tips",
        "node_getChainTips",
        "node_getBlock",
        "node_getBlocks",
        "node_getBlockData",
        "node_getBlockHeader",
        "node_getBlockByArchive",
        "node_getBlockByHash",
        "node_getBlockHeaderByArchive",

        // Checkpoints / consensus
        "node_getCheckpointNumber",
        "node_getCheckpoint",
        "node_getCheckpointedBlockNumber",
        "node_getCheckpointedBlocks",
        "node_getCheckpoints",
        "node_getCheckpointsData",
        "node_getCheckpointAttestationsForSlot",
        "node_getProposalsForSlot",

        // Transactions
        "node_sendTx",
        "node_getTxReceipt",
        "node_getTxEffect",
        "node_getTxByHash",
        "node_getTxsByHash",
        "node_getPendingTxs",
        "node_getPendingTxCount",
        "node_isValidTx",
        "node_simulatePublicCalls",

        // State / storage
        "node_getPublicStorageAt",
        "node_getWorldStateSyncStatus",
        "node_findLeavesIndexes",

        // Sync status
        "node_getSyncedL1Timestamp",
        "node_getSyncedL2EpochNumber",
        "node_getSyncedL2SlotNumber",

        // Sibling paths
        "node_getNullifierSiblingPath",
        "node_getNoteHashSiblingPath",
        "node_getArchiveSiblingPath",
        "node_getPublicDataSiblingPath",

        // Membership witnesses
        "node_getNullifierMembershipWitness",
        "node_getLowNullifierMembershipWitness",
        "node_getPublicDataWitness",
        "node_getArchiveMembershipWitness",
        "node_getNoteHashMembershipWitness",
        "node_getBlockHashMembershipWitness",
        "node_getL1ToL2MessageMembershipWitness",

        // L1 <-> L2 messages
        "node_getL1ToL2MessageBlock",
        "node_getL1ToL2MessageCheckpoint",
        "node_isL1ToL2MessageSynced",
        "node_getL2ToL1Messages",
        "node_getL2ToL1MembershipWitness",

        // Logs
        "node_getPrivateLogs",
        "node_getPrivateLogsByTags",
        "node_getPublicLogs",
        "node_getPublicLogsByTagsFromContract",
        "node_getPublicLogsByTags",
        "node_getContractClassLogs",
        "node_getLogsByTags",

        // Contracts
        "node_getContractClass",
        "node_getContract",
        "node_registerContractFunctionSignatures",

        // Node info
        "node_isReady",
        "node_getNodeInfo",
        "node_getNodeVersion",
        "node_getVersion",
        "node_getChainId",
        "node_getL1ContractAddresses",
        "node_getL1Constants",
        "node_getProtocolContractAddresses",
        "node_getEncodedEnr",

        // Fees
        "node_getCurrentBaseFees",
        "node_getCurrentMinFees",
        "node_getPredictedMinFees",
        "node_getMaxPriorityFees",

        // Validators
        "node_getValidatorsStats",
        "node_getValidatorStats",

        // Misc
        "node_getAllowedPublicSetup",
    )

    override fun createQuorumFor(method: String): CallQuorum {
        return when {
            broadcast.contains(method) -> BroadcastQuorum()
            else -> AlwaysQuorum()
        }
    }

    override fun isCallable(method: String): Boolean {
        return allowedMethods.contains(method)
    }

    override fun isHardcoded(method: String): Boolean {
        return false
    }

    override fun executeHardcoded(method: String): ByteArray {
        throw RpcException(-32601, "Method not found")
    }

    override fun getGroupMethods(groupName: String): Set<String> =
        when (groupName) {
            "default" -> getSupportedMethods()
            else -> emptySet()
        }

    override fun getSupportedMethods(): Set<String> {
        return allowedMethods.toSortedSet()
    }
}
