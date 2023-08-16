package io.emeraldpay.dshackle.auth.service

import java.security.PrivateKey
import java.security.PublicKey

interface KeyReader {

    fun getKeyPair(providerPrivateKeyPath: String, drpcPublicKeyPath: String): KeyPair

    data class KeyPair(
        val providerPrivateKey: PrivateKey,
        val drpcPublicKey: PublicKey
    )
}
