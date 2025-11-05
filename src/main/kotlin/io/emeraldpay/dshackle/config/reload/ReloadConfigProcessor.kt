package io.emeraldpay.dshackle.config.reload

import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.Global.Companion.chainById
import io.emeraldpay.dshackle.config.UpstreamsConfig
import io.emeraldpay.dshackle.foundation.ChainOptions
import org.springframework.stereotype.Component
import java.util.stream.Collectors

interface ReloadConfigProcessor {
    fun reload(): Boolean
    fun configType(): String
}

@Component
class UpstreamConfigReloadConfigProcessor(
    private val reloadConfigService: ReloadConfigService,
    private val reloadConfigUpstreamService: ReloadConfigUpstreamService,
) : ReloadConfigProcessor {
    override fun reload(): Boolean {
        val newUpstreamsConfig = reloadConfigService.readUpstreamsConfig()
        val currentUpstreamsConfig = reloadConfigService.currentUpstreamsConfig()

        if (newUpstreamsConfig == currentUpstreamsConfig) {
            return false
        }

        val chainsToReload = analyzeDefaultOptions(
            currentUpstreamsConfig.defaultOptions,
            newUpstreamsConfig.defaultOptions,
        )
        val upstreamsAnalyzeData = analyzeUpstreams(
            currentUpstreamsConfig.upstreams,
            newUpstreamsConfig.upstreams,
        )

        val upstreamsToRemove = upstreamsAnalyzeData.removed
            .filterNot { chainsToReload.contains(it.second) }
            .toSet()
        val upstreamsToAdd = upstreamsAnalyzeData.added

        reloadConfigService.updateUpstreamsConfig(newUpstreamsConfig)

        reloadConfigUpstreamService.reloadUpstreams(chainsToReload, upstreamsToRemove, upstreamsToAdd, newUpstreamsConfig)

        return true
    }

    override fun configType(): String {
        return "upstream config"
    }

    private fun analyzeUpstreams(
        currentUpstreams: List<UpstreamsConfig.Upstream<*>>,
        newUpstreams: List<UpstreamsConfig.Upstream<*>>,
    ): UpstreamAnalyzeData {
        if (currentUpstreams == newUpstreams) {
            return UpstreamAnalyzeData()
        }
        val reloaded = mutableSetOf<Pair<String, Chain>>()
        val removed = mutableSetOf<Pair<String, Chain>>()
        val currentUpstreamsMap = currentUpstreams.associateBy { it.id!! to chainById(it.chain) }
        val newUpstreamsMap = newUpstreams.associateBy { it.id!! to chainById(it.chain) }

        currentUpstreamsMap.forEach {
            val newUpstream = newUpstreamsMap[it.key]
            if (newUpstream == null) {
                removed.add(it.key)
            } else if (newUpstream != it.value) {
                reloaded.add(it.key)
            }
        }

        val added = newUpstreamsMap
            .minus(currentUpstreamsMap.keys)
            .mapTo(mutableSetOf()) { it.key }
            .plus(reloaded)

        return UpstreamAnalyzeData(added, removed.plus(reloaded))
    }

    private fun analyzeDefaultOptions(
        currentDefaultOptions: List<ChainOptions.DefaultOptions>,
        newDefaultOptions: List<ChainOptions.DefaultOptions>,
    ): Set<Chain> {
        val chainsToReload = mutableSetOf<Chain>()

        val currentOptions = getChainOptions(currentDefaultOptions)
        val newOptions = getChainOptions(newDefaultOptions)

        if (currentOptions == newOptions) {
            return emptySet()
        }

        val removed = mutableSetOf<Chain>()

        currentOptions.forEach {
            val newChainOption = newOptions[it.key]
            if (newChainOption == null) {
                removed.add(chainById(it.key))
            } else if (newChainOption != it.value) {
                chainsToReload.add(chainById(it.key))
            }
        }

        val added = newOptions.minus(currentOptions.keys).map { chainById(it.key) }

        return chainsToReload.plus(added).plus(removed)
    }

    private fun getChainOptions(
        defaultOptions: List<ChainOptions.DefaultOptions>,
    ): Map<String, List<ChainOptions.PartialOptions>> {
        return defaultOptions.stream()
            .flatMap { options -> options.chains?.stream()?.map { it to options.options } }
            .collect(
                Collectors.groupingBy(
                    { it.first },
                    Collectors.mapping(
                        { it.second },
                        Collectors.toUnmodifiableList(),
                    ),
                ),
            )
    }

    private data class UpstreamAnalyzeData(
        val added: Set<Pair<String, Chain>> = emptySet(),
        val removed: Set<Pair<String, Chain>> = emptySet(),
    )
}
