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
        "node_getBlockNumber",
        "node_getProvenBlockNumber",
        "node_getL2Tips",
        "node_getBlock",
        "node_getBlocks",
        "node_getBlockHeader",
        "node_sendTx",
        "node_getTxReceipt",
        "node_getTxEffect",
        "node_getTxByHash",
        "node_getPendingTxs",
        "node_getPendingTxCount",
        "node_isValidTx",
        "node_simulatePublicCalls",
        "node_getPublicStorageAt",
        "node_getWorldStateSyncStatus",
        "node_findLeavesIndexes",
        "node_getNullifierSiblingPath",
        "node_getNoteHashSiblingPath",
        "node_getArchiveSiblingPath",
        "node_getPublicDataSiblingPath",
        "node_getNullifierMembershipWitness",
        "node_getLowNullifierMembershipWitness",
        "node_getPublicDataWitness",
        "node_getArchiveMembershipWitness",
        "node_getNoteHashMembershipWitness",
        "node_getL1ToL2MessageMembershipWitness",
        "node_getL1ToL2MessageBlock",
        "node_isL1ToL2MessageSynced",
        "node_getL2ToL1Messages",
        "node_getPrivateLogs",
        "node_getPublicLogs",
        "node_getContractClassLogs",
        "node_getLogsByTags",
        "node_getContractClass",
        "node_getContract",
        "node_isReady",
        "node_getNodeInfo",
        "node_getNodeVersion",
        "node_getVersion",
        "node_getChainId",
        "node_getL1ContractAddresses",
        "node_getProtocolContractAddresses",
        "node_getEncodedEnr",
        "node_getCurrentBaseFees",
        "node_getValidatorsStats",
        "node_getValidatorStats",
        "node_registerContractFunctionSignatures",
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
