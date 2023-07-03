/**
 * Copyright (c) 2020 EmeraldPay, Inc
 * Copyright (c) 2019 ETCDEV GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.emeraldpay.dshackle.upstream.ethereum

import io.emeraldpay.dshackle.reader.JsonRpcReader
import io.emeraldpay.dshackle.upstream.BlockValidator
import io.emeraldpay.dshackle.upstream.Lifecycle
import io.emeraldpay.dshackle.upstream.forkchoice.ForkChoice
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.scheduler.Scheduler
import java.time.Duration

class EthereumRpcHead(
    private val api: JsonRpcReader,
    forkChoice: ForkChoice,
    upstreamId: String,
    blockValidator: BlockValidator,
    private val headScheduler: Scheduler,
    private val interval: Duration = Duration.ofSeconds(10),
) : DefaultEthereumHead(upstreamId, forkChoice, blockValidator, headScheduler), Lifecycle {

    private var refreshSubscription: Disposable? = null
    private var isSyncing = false

    override fun start() {
        super.start()
        refreshSubscription?.dispose()
        val base = Flux.interval(interval)
            .publishOn(headScheduler)
            .filter { !isSyncing }
            .flatMap {
                getLatestBlock(api)
            }
        refreshSubscription = super.follow(base)
    }

    override fun isRunning(): Boolean {
        return refreshSubscription != null
    }

    override fun onSyncingNode(isSyncing: Boolean) {
        this.isSyncing = isSyncing
    }

    override fun stop() {
        super.stop()
        refreshSubscription?.dispose()
        refreshSubscription = null
    }
}
