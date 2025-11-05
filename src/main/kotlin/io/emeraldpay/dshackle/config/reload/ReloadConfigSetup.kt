package io.emeraldpay.dshackle.config.reload

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import sun.misc.Signal
import sun.misc.SignalHandler
import java.util.concurrent.locks.ReentrantLock

@Component
class ReloadConfigSetup(
    private val processors: List<ReloadConfigProcessor>,
) : SignalHandler {

    companion object {
        private val log = LoggerFactory.getLogger(this::class.java)
        private val signalHup = Signal("HUP")
    }

    private val reloadLock = ReentrantLock()

    init {
        Signal.handle(signalHup, this)
    }

    override fun handle(sig: Signal) {
        if (sig == signalHup) {
            try {
                handle()
            } catch (e: Exception) {
                log.warn("Config is not reloaded, cause - ${e.message}", e)
            }
        }
    }

    private fun handle() {
        if (reloadLock.tryLock()) {
            try {
                log.info("Reloading config...")

                if (processors.isEmpty()) {
                    log.warn("No reload config processors")
                    return
                }

                processors.forEach {
                    if (it.reload()) {
                        log.info("{} is reloaded", it.configType())
                    } else {
                        log.info("There is nothing to reload, {} is the same", it.configType())
                    }
                }
            } finally {
                log.info("Reloading config has been completed")
                reloadLock.unlock()
            }
        } else {
            log.warn("Reloading is in progress")
        }
    }
}
