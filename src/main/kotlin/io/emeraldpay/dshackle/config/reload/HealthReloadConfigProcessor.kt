package io.emeraldpay.dshackle.config.reload

import org.springframework.stereotype.Component

@Component
class HealthReloadConfigProcessor(
    private val reloadConfigService: ReloadConfigService,
) : ReloadConfigProcessor {
    override fun reload(): Boolean {
        val currentCfg = reloadConfigService.currentHealthConfig()
        val newCfg = reloadConfigService.readHealthConfig()

        if (currentCfg.configs().toSet() == newCfg.configs().toSet()) {
            return false
        }

        reloadConfigService.updateHealthChains(newCfg.loadChains())

        return true
    }

    override fun configType(): String {
        return "health config"
    }
}
