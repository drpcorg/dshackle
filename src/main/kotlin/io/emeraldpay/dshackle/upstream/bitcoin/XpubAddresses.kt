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
package io.emeraldpay.dshackle.upstream.bitcoin

import org.bitcoinj.base.Address
import org.bitcoinj.base.BitcoinNetwork
import org.bitcoinj.base.LegacyAddress
import org.bitcoinj.base.ScriptType
import org.bitcoinj.base.SegwitAddress
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.ECKey
import org.bitcoinj.crypto.HDKeyDerivation
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.util.function.Tuples
import java.util.concurrent.atomic.AtomicInteger

open class XpubAddresses(
    private val addressActiveCheck: AddressActiveCheck,
) {

    companion object {
        private val log = LoggerFactory.getLogger(XpubAddresses::class.java)
        private val INACTIVE_LIMIT = 20
    }

    open fun allAddresses(xpub: String, start: Int, limit: Int): Flux<Address> {
        // versions:
        // https://electrum.readthedocs.io/en/latest/xpub_version_bytes.html
        // TODO doesn't support SH keys right now. should?
        val prefix = xpub.substring(0, 4)
        val type: ScriptType
        val params: NetworkParameters

        when (prefix) {
            "xpub" -> {
                type = ScriptType.P2PKH
                params = NetworkParameters.of(BitcoinNetwork.MAINNET)
            }
            "zpub" -> {
                type = ScriptType.P2WPKH
                params = NetworkParameters.of(BitcoinNetwork.MAINNET)
            }
            "tpub" -> {
                type = ScriptType.P2PKH
                params = NetworkParameters.of(BitcoinNetwork.TESTNET)
            }
            "vpub" -> {
                type = ScriptType.P2WPKH
                params = NetworkParameters.of(BitcoinNetwork.TESTNET)
            }
            else -> return Flux.error(IllegalArgumentException("Unsupported type: $prefix"))
        }

        val key: DeterministicKey
        try {
            key = DeterministicKey.deserializeB58(xpub, params)
        } catch (t: Throwable) {
            return Flux.error(t)
        }

        return Flux.range(start, limit)
            .map { HDKeyDerivation.deriveChildKey(key, ChildNumber(it, false)) }
            .map { addressFromKey(params, ECKey.fromPublicOnly(it.pubKey), type) }
    }

    private fun addressFromKey(params: NetworkParameters, key: ECKey, type: ScriptType): Address = when (type) {
        ScriptType.P2PKH -> LegacyAddress.fromKey(params, key)
        ScriptType.P2WPKH -> SegwitAddress.fromKey(params, key)
        else -> throw IllegalArgumentException("Unsupported script type: $type")
    }

    open fun activeAddresses(xpub: String, start: Int, limit: Int): Flux<Address> {
        val lastActive = AtomicInteger(0)
        return this.allAddresses(xpub, start, limit)
            .zipWith(Flux.range(0, limit))
            .takeUntil {
                it.t2 - lastActive.get() >= INACTIVE_LIMIT
            }
            .concatMap { toCheck ->
                addressActiveCheck.isActive(toCheck.t1)
                    .doOnNext { active -> if (active) lastActive.set(toCheck.t2) }
                    .map { Tuples.of(toCheck.t1, it) }
            }
            .filter {
                it.t2
            }
            .map {
                it.t1
            }
    }
}
