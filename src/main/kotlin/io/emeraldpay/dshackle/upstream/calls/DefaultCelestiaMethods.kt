package io.emeraldpay.dshackle.upstream.calls

import io.emeraldpay.dshackle.quorum.AlwaysQuorum
import io.emeraldpay.dshackle.quorum.CallQuorum
import io.emeraldpay.dshackle.upstream.ethereum.rpc.RpcException

/**
 * The read-only surface of the Celestia DA node (celestia-node) JSON-RPC API.
 * Write methods (blob.Submit, state transactions) and admin/p2p methods are
 * deliberately absent: they spend or expose the node's own wallet.
 */
class DefaultCelestiaMethods : CallMethods {

    private val allowedMethods: Set<String> = setOf(
        // header
        "header.LocalHead",
        "header.GetByHash",
        "header.GetByHeight",
        "header.GetRangeByHeight",
        "header.WaitForHeight",
        "header.SyncState",
        "header.SyncWait",
        "header.NetworkHead",
        "header.Tail",

        // blob
        "blob.Get",
        "blob.GetAll",
        "blob.GetProof",
        "blob.Included",
        "blob.GetCommitmentProof",

        // share
        "share.SharesAvailable",
        "share.GetShare",
        "share.GetSamples",
        "share.GetEDS",
        "share.GetRow",
        "share.GetNamespaceData",
        "share.GetRange",

        // das
        "das.SamplingStats",
        "das.WaitCatchUp",

        // blobstream
        "blobstream.GetDataRootTupleRoot",
        "blobstream.GetDataRootTupleInclusionProof",

        // state (read-only queries)
        "state.BalanceForAddress",
        "state.QueryDelegation",
        "state.QueryUnbonding",
        "state.QueryRedelegations",
        "state.QueryDelegationRewards",

        // node
        "node.Ready",
    )

    override fun createQuorumFor(method: String): CallQuorum {
        return AlwaysQuorum()
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
