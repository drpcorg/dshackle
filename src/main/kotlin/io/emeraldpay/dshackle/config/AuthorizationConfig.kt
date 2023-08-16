package io.emeraldpay.dshackle.config

data class AuthorizationConfig(
    val enabled: Boolean,
    val providerPrivateKeyPath: String,
    val drpcPublicKeyPath: String
) {

    companion object {
        @JvmStatic
        fun default() = AuthorizationConfig(false, "", "")
    }
}
