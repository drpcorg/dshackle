package io.emeraldpay.dshackle.config.hot

import io.emeraldpay.dshackle.Global
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.core5.http.io.entity.EntityUtils
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier

class AutoReloadbleConfig<T>(
    val initialContent: String,
    val configUrl: String,
    private val type: Class<T>,
) : Supplier<T?> {
    companion object {
        private val log = LoggerFactory.getLogger(AutoReloadbleConfig::class.java)
    }

    private val httpClient = HttpClients.createDefault()
    private val instance = AtomicReference<T>()

    fun reload() {
        try {
            val getReq = HttpGet(configUrl)
            val response = httpClient.execute<String>(getReq) { resp -> EntityUtils.toString(resp.entity) }
            if (response != null) {
                instance.set(parseConfig(response))
            }
            println()
        } catch (e: Exception) {
            log.error("Failed to reload config from $configUrl for type $type", e)
        }
    }

    fun start() {
        instance.set(parseConfig(initialContent))
        Flux.interval(
            Duration.ofSeconds(1),
        ).subscribe {
            reload()
        }
    }

    private fun parseConfig(config: String): T {
        return Global.yamlMapper.readValue(config, type)
    }

    override fun get(): T {
        return instance.get()
    }
}
