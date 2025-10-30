package io.emeraldpay.dshackle.config.reload

import io.emeraldpay.dshackle.monitoring.HealthCheckSetup
import org.springframework.stereotype.Component

@Component
class ReloadConfigHealthService(
    private val healthCheckSetup: HealthCheckSetup,
) {

    fun reloadHealth() {
        healthCheckSetup.reload()
    }
}
