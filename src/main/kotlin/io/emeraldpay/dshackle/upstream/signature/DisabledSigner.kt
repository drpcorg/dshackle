package io.emeraldpay.dshackle.upstream.signature

import io.emeraldpay.dshackle.upstream.ethereum.rpc.RpcException
import io.emeraldpay.dshackle.upstream.ethereum.rpc.RpcResponseError

class DisabledSigner : ResponseSigner {
    override val enabled: Boolean = false

    override fun sign(nonce: Long, message: ByteArray, source: String): ResponseSigner.Signature {
        throw RpcException(
            RpcResponseError.CODE_INTERNAL_ERROR,
            "Response signing requested via nonce but signing key is not configured",
        )
    }
}
