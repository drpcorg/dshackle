package io.emeraldpay.dshackle.auth

import java.time.Instant

class AuthContext {

    companion object {
        @Volatile
        var tokenWrapper: TokenWrapper? = null
            private set

        fun putTokenInContext(tokenWrapper: TokenWrapper?) {
            this.tokenWrapper = tokenWrapper
        }
    }

    data class TokenWrapper(
        val token: String,
        val lastAuthAt: Instant
    )
}
