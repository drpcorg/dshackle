package io.emeraldpay.dshackle.auth.processor

import io.emeraldpay.dshackle.auth.AuthContext
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

@Component
open class TokenProcessor {

    @Scheduled(fixedRate = 30000)
    fun invalidateTokens() {
        AuthContext.sessions
            .filter { Instant.now().isAfter(it.value.issuedAt.plus(1, ChronoUnit.HOURS)) }
            .forEach {
                AuthContext.removeToken(it.key)
            }
    }
}
