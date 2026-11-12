/**
 * Copyright (c) 2020 EmeraldPay, Inc
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
package io.emeraldpay.dshackle.config

class CacheConfig {

    var redis: Redis? = null
    var recentBlocks: RecentBlocks = RecentBlocks()
    var responses: Responses = Responses()

    /**
     * Serve `eth_getBlockByNumber(<recent number or latest>, false)` from memory.
     * ```
     * cache:
     *   recent-blocks:
     *     enabled: true
     *     warm-up: true
     * ```
     */
    class RecentBlocks(
        var enabled: Boolean = false,
        // fetch the body of new head blocks ahead of requests, while the chain gets block-by-number requests
        var warmUp: Boolean = true,
    )

    /**
     * Cache responses that can't change (blocks, receipts and logs of one block, state at a fixed block).
     * ```
     * cache:
     *   responses:
     *     enabled: true
     *     max-memory-mb: 32
     * ```
     */
    class Responses(
        var enabled: Boolean = false,
        // per blockchain
        var maxMemoryMb: Int = 32,
    )

    class Redis(
        var host: String = "127.0.0.1",
        var port: Int = 6379,
        var db: Int? = 0,
        var password: String? = null,
    )
}
