package io.emeraldpay.dshackle.upstream.signature

import io.emeraldpay.dshackle.upstream.ethereum.rpc.RpcException
import io.emeraldpay.dshackle.upstream.ethereum.rpc.RpcResponseError

class NoSigner : ResponseSigner {
    override fun sign(nonce: Long, message: ByteArray, source: String): ResponseSigner.Signature {
        throw RpcException(
            RpcResponseError.CODE_INTERNAL_ERROR,
            "Signing is not configured",
        )
    }
}
