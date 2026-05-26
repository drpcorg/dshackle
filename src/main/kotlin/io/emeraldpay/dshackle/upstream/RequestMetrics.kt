/**
 * Copyright (c) 2021 EmeraldPay, Inc
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
package io.emeraldpay.dshackle.upstream

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Timer
import java.util.concurrent.ConcurrentHashMap

class RequestMetrics(
    private val timerFactory: (String?) -> Timer,
    val fails: Counter,
    val nettyMetricsEnabled: Boolean,
) {
    private val timerCache = ConcurrentHashMap<String, Timer>()

    constructor(timer: Timer, fails: Counter, nettyMetricsEnabled: Boolean) :
        this({ _ -> timer }, fails, nettyMetricsEnabled)

    fun timer(method: String? = null): Timer =
        timerCache.computeIfAbsent(method ?: "") { timerFactory(method) }

    fun registeredTimers(): Collection<Timer> = timerCache.values
}
