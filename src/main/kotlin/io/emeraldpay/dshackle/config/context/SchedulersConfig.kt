package io.emeraldpay.dshackle.config.context

import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.scheduling.concurrent.CustomizableThreadFactory
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@Configuration
@DependsOn("monitoringSetup")
open class SchedulersConfig {

    companion object {
        private val log = LoggerFactory.getLogger(SchedulersConfig::class.java)
        val threadsMultiplier = run {
            val cores = Runtime.getRuntime().availableProcessors()
            if (cores < 3) {
                1
            } else {
                cores / 2
            }
        }.also { log.info("Creating schedulers with multiplier: {}...", it) }
    }

    @Bean
    open fun rpcScheduler(): Scheduler {
        return makeScheduler("blockchain-rpc-scheduler", 20)
    }

    @Bean
    open fun headScheduler(): Scheduler {
        return makeScheduler("head-scheduler", 4)
    }

    @Bean
    open fun subScheduler(): Scheduler {
        return makeScheduler("sub-scheduler", 4)
    }

    @Bean
    open fun multistreamEventsScheduler(): Scheduler {
        return makeScheduler("events-scheduler", 4)
    }

    @Bean
    open fun wsConnectionResubscribeScheduler(): Scheduler {
        return makeScheduler("ws-connection-resubscribe-scheduler", 2)
    }

    @Bean
    open fun wsScheduler(): Scheduler {
        return makeScheduler("ws-scheduler", 4)
    }

    @Bean
    open fun headLivenessScheduler(): Scheduler {
        return makeScheduler("head-liveness-scheduler", 4)
    }

    @Bean
    open fun grpcChannelExecutor(): Executor {
        return makePool("grpc-client-channel", 10)
    }

    @Bean
    open fun authScheduler(): Scheduler {
        return makeScheduler("auth-scheduler", 4)
    }

    @Bean
    open fun httpScheduler(): Scheduler {
        return makeScheduler("http-scheduler", 30)
    }

    @Bean
    open fun eventsScheduler(): Scheduler {
        return makeScheduler("ws-events-scheduler", 30)
    }

    private fun makeScheduler(name: String, size: Int): Scheduler {
        return Schedulers.fromExecutorService(makePool(name, size))
    }

    private fun makePool(name: String, size: Int): ExecutorService {
        val cachedPool = ThreadPoolExecutor(
            size,
            size * threadsMultiplier,
            60L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(10000),
            CustomizableThreadFactory("$name-"),
        )

        return ExecutorServiceMetrics.monitor(
            Metrics.globalRegistry,
            cachedPool,
            name,
        )
    }
}
