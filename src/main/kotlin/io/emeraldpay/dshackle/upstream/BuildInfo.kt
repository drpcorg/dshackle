package io.emeraldpay.dshackle.upstream

import io.emeraldpay.api.proto.BlockchainOuterClass

class BuildInfo(version: String?) {
    var version = version
        private set

    fun update(buildInfo: BuildInfo): Boolean {
        val changed = buildInfo.version != version
        version = buildInfo.version
        return changed
    }

    companion object {
        fun extract(buildInfo: BlockchainOuterClass.BuildInfo): BuildInfo {
            return BuildInfo(buildInfo.version)
        }
    }
}
