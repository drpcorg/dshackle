package io.emeraldpay.dshackle.upstream.lowerbound

import io.emeraldpay.dshackle.config.UpstreamsConfig
import io.emeraldpay.dshackle.upstream.Upstream

interface ManualLowerBoundService {
    fun manualLowerBound(type: LowerBoundType): LowerBoundData?
    fun hasManualBound(type: LowerBoundType): Boolean
    fun manualBoundTypes(): Set<LowerBoundType>
}

class NoopManualLowerBoundService : ManualLowerBoundService {
    override fun manualLowerBound(type: LowerBoundType): LowerBoundData? {
        return null
    }

    override fun hasManualBound(type: LowerBoundType): Boolean {
        return false
    }

    override fun manualBoundTypes(): Set<LowerBoundType> {
        return emptySet()
    }
}

class BaseManualLowerBoundService(
    private val upstream: Upstream,
    private val manualLowerBoundSettings: Map<LowerBoundType, UpstreamsConfig.ManualBoundSetting>,
) : ManualLowerBoundService {
    private val providers = manualLowerBoundSettings.mapValues { provider(it.value) }

    override fun manualLowerBound(type: LowerBoundType): LowerBoundData? {
        val provider = providers[type] ?: return null
        if (!provider.canHandle()) {
            return null
        }
        return LowerBoundData(provider.getManualLowerBound(), type)
    }

    override fun hasManualBound(type: LowerBoundType): Boolean {
        return manualLowerBoundSettings.contains(type)
    }

    override fun manualBoundTypes(): Set<LowerBoundType> {
        return manualLowerBoundSettings.keys
    }

    private fun provider(setting: UpstreamsConfig.ManualBoundSetting): ManualLowerBoundProvider {
        return when (setting.type) {
            ManualLowerBoundType.HEAD -> HeadManualLowerBoundProvider(upstream, setting)
            ManualLowerBoundType.FIXED -> FixedManualLowerBoundProvider(setting)
        }
    }
}

interface ManualLowerBoundProvider {
    fun getManualLowerBound(): Long
    fun canHandle(): Boolean
}

class HeadManualLowerBoundProvider(
    private val upstream: Upstream,
    private val setting: UpstreamsConfig.ManualBoundSetting,
) : ManualLowerBoundProvider {
    override fun getManualLowerBound(): Long {
        return upstream.getHead().getCurrentHeight()!! + setting.value
    }

    override fun canHandle(): Boolean {
        return setting.type == ManualLowerBoundType.HEAD &&
            setting.value < 0 &&
            upstream.getHead().getCurrentHeight() != null &&
            upstream.getHead().getCurrentHeight()!! + setting.value >= 0
    }
}

class FixedManualLowerBoundProvider(
    private val setting: UpstreamsConfig.ManualBoundSetting,
) : ManualLowerBoundProvider {
    override fun getManualLowerBound(): Long {
        return setting.value
    }

    override fun canHandle(): Boolean {
        return setting.type == ManualLowerBoundType.FIXED && setting.value > 0
    }
}
