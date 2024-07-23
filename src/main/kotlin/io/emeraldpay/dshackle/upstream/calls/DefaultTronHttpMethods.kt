package io.emeraldpay.dshackle.upstream.calls

import io.emeraldpay.dshackle.quorum.AlwaysQuorum
import io.emeraldpay.dshackle.quorum.CallQuorum
import io.emeraldpay.dshackle.upstream.ethereum.rpc.RpcException

class DefaultTronHttpMethods : CallMethods {

    // HTTP API section
    private val blockHttpMethods = setOf(
        postMethod("/wallet/getblock"),
        postMethod("/wallet/getblockbynum"),
        postMethod("/wallet/getblockbyid"),
        postMethod("/wallet/getblockbylatestnum"),
        postMethod("/wallet/getblockbylimitnext"),
        postMethod("/wallet/getnowblock"),

        postMethod("/wallet/gettransactionbyid"),
        postMethod("/wallet/gettransactioninfobyid"),
        postMethod("/wallet/gettransactioninfobyblocknum"),

        getMethod("/wallet/listnodes"),
        getMethod("/wallet/getnodeinfo"),
        getMethod("/wallet/getchainparameters"),
        postMethod("/wallet/getblockbalance"),
        getMethod("/wallet/getenergyprices"),
        getMethod("/wallet/getbandwidthprices"),
        getMethod("/wallet/getburntrx"),
        getMethod("/wallet/getapprovedlist"),
    )

    private val accountHttpMethods = setOf(
        postMethod("/wallet/validateaddress"),
        postMethod("/wallet/createaccount"),
        postMethod("/wallet/getaccount"),
        postMethod("/wallet/updateaccount"),
        postMethod("/wallet/accountpermissionupdate"),
        postMethod("/wallet/getaccountbalance"),

        postMethod("/wallet/getaccountresource"),
        postMethod("/wallet/getaccountnet"),
        postMethod("/wallet/freezebalance"),
        postMethod("/wallet/unfreezebalance"),
        postMethod("/wallet/getdelegatedresource"),
        postMethod("/wallet/getdelegatedresourceaccountindex"),
        postMethod("/wallet/freezebalancev2"),
        postMethod("/wallet/unfreezebalancev2"),
        postMethod("/wallet/cancelallunfreezev2"),
        postMethod("/wallet/delegateresource"),
        postMethod("/wallet/undelegateresource"),
        postMethod("/wallet/withdrawexpireunfreeze"),
        postMethod("/wallet/getavailableunfreezecount"),
        postMethod("/wallet/getcanwithdrawunfreezeamount"),
        postMethod("/wallet/getcandelegatedmaxsize"),
        postMethod("/wallet/getdelegatedresourcev2"),
        postMethod("/wallet/getdelegatedresourceaccountindexv2"),
    )

    private val transactionHttpMethods = setOf(
        postMethod("/wallet/broadcasttransaction"),
        postMethod("/wallet/broadcasthex"),
        postMethod("/wallet/createtransaction"),
        )

    private val trc10HttpMethods = setOf(
        postMethod("/wallet/getassetissuebyaccount"),
        postMethod("/wallet/getassetissuebyid"),
        postMethod("/wallet/getassetissuebyname"),
        getMethod("/wallet/getassetissuelist"),
        postMethod("/wallet/getassetissuelistbyname"),
        postMethod("/wallet/getpaginatedassetissuelist"),
        postMethod("/wallet/transferasset"),
        postMethod("/wallet/createassetissue"),
        postMethod("/wallet/participateassetissue"),
        postMethod("/wallet/unfreezeasset"),
        postMethod("/wallet/updateasset"),
        )

    private val smartContractHttpMethods = setOf(
        postMethod("/wallet/getcontract"),
        postMethod("/wallet/getcontractinfo"),
        postMethod("/wallet/triggersmartcontract"),
        postMethod("/wallet/triggerconstantcontract"),
        postMethod("/wallet/deploycontract"),
        postMethod("/wallet/updatesetting"),
        postMethod("/wallet/updateenergylimit"),
        postMethod("/wallet/clearabi"),
        //postMethod("/wallet/estimateenergy"), // disabled by default
        )

    //commented methods are disabled on trongrid for security reasons
    private val tronzSmartContractHttpMethods = setOf(
        //getMethod("/wallet/getspendingkey"),
        //postMethod("/wallet/getexpandedspendingkey"),
        //postMethod("/wallet/getakfromask"),
        //postMethod("/wallet/getnkfromnsk"),
        postMethod("/wallet/getincomingviewingkey"),
        getMethod("/wallet/getdiversifier"),
        postMethod("/wallet/getzenpaymentaddress"),
        //getMethod("/wallet/getnewshieldedaddress"),
        //postMethod("/wallet/createshieldedcontractparameters"),
        //postMethod("/wallet/createspendauthsig"),
        postMethod("/wallet/gettriggerinputforshieldedtrc20contract"),
        postMethod("/wallet/scanshieldedtrc20notesbyivk"),
        postMethod("/wallet/scanshieldedtrc20notesbyovk"),
        postMethod("/wallet/isshieldedtrc20contractnotespent"),
        )

    private val votingHttpMethods = setOf(
        getMethod("/wallet/listwitnesses"),
        postMethod("/wallet/createwitnesses"),
        postMethod("/wallet/updatewitness"),
        postMethod("/wallet/getBrokerage"), // for some reason camel case is only here
        postMethod("/wallet/updateBrokerage"),
        postMethod("/wallet/votewitnessaccount"),
        postMethod("/wallet/getReward"),
        postMethod("/wallet/withdrawbalance"),
        getMethod("/wallet/getnextmaintenancetime"),
        )

    private val proposalHttpMethods = setOf(
        getMethod("/wallet/listproposals"),
        postMethod("/wallet/getproposalbyid"),
        postMethod("/wallet/proposalcreate"),
        postMethod("/wallet/proposalapprove"),
        postMethod("/wallet/proposaldelete"),
        )

    private val dexHttpMethods = setOf(
        getMethod("/wallet/listexchanges"),
        postMethod("/wallet/getexchangebyid"),
        postMethod("/wallet/exchangecreate"),
        postMethod("/wallet/exchangeinject"),
        postMethod("/wallet/exchangewithdraw"),
        postMethod("/wallet/exchangetransaction"),
    )

    private val pendingPoolHttpMethods = setOf(
        getMethod("/wallet/gettransactionlistfrompending"),
        postMethod("/wallet/gettransactionfrompending"),
        getMethod("/wallet/getpendingsize"),
    )

    private val allowedHttpMethods: Set<String> = blockHttpMethods +
        accountHttpMethods +
        transactionHttpMethods +
        trc10HttpMethods +
        smartContractHttpMethods +
        tronzSmartContractHttpMethods +
        votingHttpMethods +
        proposalHttpMethods +
        dexHttpMethods +
        pendingPoolHttpMethods

    // HTTP-Solidity section
    private val transactionHttpSolidityMethods = setOf(
        postMethod("/walletsolidity/gettransactionbyid"),
        postMethod("/walletsolidity/gettransactioninfobyid"),
        postMethod("/walletsolidity/gettransactionbyblocknum"),
        postMethod("/walletsolidity/gettransactioncountbyblocknum"),
        )

    private val blockHttpSolidityMethods = setOf(
        postMethod("/walletsolidity/getblock"),
        getMethod("/walletsolidity/getnowblock"),
        postMethod("/walletsolidity/getblockbynum"),
        postMethod("/walletsolidity/getblockbyid"),
        postMethod("/walletsolidity/getblockbylatestnum"),
        postMethod("/walletsolidity/getblockbylimitnext"),
    )

    private val accountHttpSolidityMethods = setOf(
        postMethod("/walletsolidity/getaccount"),
        postMethod("/walletsolidity/getdelegatedresource"),
        postMethod("/walletsolidity/getdelegatedresourceaccountindex"),
        postMethod("/walletsolidity/getcandelegatedmaxsize"),
        postMethod("/walletsolidity/getcanwithdrawunfreezeamount"),
        postMethod("/walletsolidity/getdelegatedresourceaccountindexv2"),
        postMethod("/walletsolidity/getavailableunfreezecount"),
        )

    private val nodeHttpSolidityMethods = setOf(
        getMethod("/walletsolidity/getnodeinfo"),
        getMethod("/walletsolidity/getburntrx"),
    )
    private val smartContractHttpSolidityMethods = setOf(
        postMethod("/walletsolidity/trigerconstantcontract"),
        //postMethod("/walletsolidity/estimateenergy"), // disabled by default
    )
    private val trc10HttpSolidityMethods = setOf(
        postMethod("/walletsolidity/getassetissuebyid"),
        postMethod("/walletsolidity/getassetissuebyname"),
        getMethod("/walletsolidity/getassetissuelist"),
        postMethod("/walletsolidity/getassetissuelistbyname"),
        postMethod("/walletsolidity/getpaginatedassetissuelist"),
    )
    private val dexHttpSolidityMethods = setOf(
        getMethod("/walletsolidity/listexchanges"),
        postMethod("/walletsolidity/getexchangebyid"),
    )

    private val votingHttpSolidityMethods = setOf(
        getMethod("/walletsolidity/listwitnesses"),
        postMethod("/walletsolidity/getBrokerage"), // for some reason camel case is only here
        postMethod("/walletsolidity/getReward"),
    )

    private val tronzSmartContractHttpSolidityMethods = setOf(
        postMethod("/walletsolidity/scanshieldedtrc20notesbyivk"),
        postMethod("/walletsolidity/scanshieldedtrc20notesbyovk"),
        postMethod("/walletsolidity/isshieldedtrc20contractnotespent"),
    )
    private val allowedHttpSolidityMethods = transactionHttpSolidityMethods +
        blockHttpSolidityMethods +
        accountHttpSolidityMethods +
        nodeHttpSolidityMethods +
        smartContractHttpSolidityMethods +
        trc10HttpSolidityMethods +
        dexHttpSolidityMethods +
        votingHttpSolidityMethods +
        tronzSmartContractHttpSolidityMethods

    override fun createQuorumFor(method: String): CallQuorum {
        return AlwaysQuorum()
    }

    override fun isCallable(method: String): Boolean {
        return allowedHttpMethods.contains(method)
    }

    override fun getSupportedMethods(): Set<String> {
        return allowedHttpMethods.toSortedSet()
    }

    override fun isHardcoded(method: String): Boolean {
        return false
    }

    override fun executeHardcoded(method: String): ByteArray {
        throw RpcException(-32601, "Method not found")
    }

    override fun getGroupMethods(groupName: String): Set<String> {
        return when (groupName) {
            "default" -> getTronHttpMethods()
            "tron_http" -> getTronHttpMethods()
            "tron_http_solidity" -> getTronHttpSolidityMethods()
            else -> emptyList()
        }.toSet()
    }

    private fun getTronHttpSolidityMethods(): List<String> =
        allowedHttpSolidityMethods.toList()

    private fun getTronHttpMethods(): List<String> =
        allowedHttpMethods.toList()

    private fun getMethod(method: String) = "GET#$method"

    private fun postMethod(method: String) = "POST#$method"
}
