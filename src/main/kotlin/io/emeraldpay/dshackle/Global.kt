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
package io.emeraldpay.dshackle

import com.fasterxml.jackson.core.Version
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.emeraldpay.dshackle.upstream.bitcoin.data.EsploraUnspent
import io.emeraldpay.dshackle.upstream.bitcoin.data.EsploraUnspentDeserializer
import io.emeraldpay.dshackle.upstream.bitcoin.data.RpcUnspent
import io.emeraldpay.dshackle.upstream.bitcoin.data.RpcUnspentDeserializer
import io.emeraldpay.dshackle.upstream.ethereum.subscribe.json.TransactionIdSerializer
import io.emeraldpay.dshackle.upstream.rpcclient.JsonRpcRequest
import io.emeraldpay.dshackle.upstream.rpcclient.JsonRpcResponse
import io.emeraldpay.etherjar.domain.TransactionId
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

class Global {

    companion object {

        val nullValue: ByteArray = "null".toByteArray()

        var metricsExtended = false

        val chainNames = mapOf(
            "ethereum" to Chain.ETHEREUM__MAINNET,
            "ethereum-classic" to Chain.ETHEREUM_CLASSIC__MAINNET,
            "eth" to Chain.ETHEREUM__MAINNET,
            "polygon" to Chain.POLYGON_POS__MAINNET,
            "matic" to Chain.POLYGON_POS__MAINNET,
            "arbitrum" to Chain.ARBITRUM__MAINNET,
            "arb" to Chain.ARBITRUM__MAINNET,
            "optimism" to Chain.OPTIMISM__MAINNET,
            "binance" to Chain.BSC__MAINNET,
            "bsc" to Chain.BSC__MAINNET,
            "bnb-smart-chain" to Chain.BSC__MAINNET,
            "bsc-testnet" to Chain.BSC__TESTNET,
            "etc" to Chain.ETHEREUM_CLASSIC__MAINNET,
            "morden" to Chain.ETHEREUM__MORDEN,
            "kovan" to Chain.ETHEREUM__KOVAN,
            "kovan-testnet" to Chain.ETHEREUM__KOVAN,
            "goerli" to Chain.ETHEREUM__GOERLI,
            "goerli-testnet" to Chain.ETHEREUM__GOERLI,
            "rinkeby" to Chain.ETHEREUM__RINKEBY,
            "rinkeby-testnet" to Chain.ETHEREUM__RINKEBY,
            "ropsten" to Chain.ETHEREUM__ROPSTEN,
            "ropsten-testnet" to Chain.ETHEREUM__ROPSTEN,
            "bitcoin" to Chain.BITCOIN__MAINNET,
            "bitcoin-testnet" to Chain.BITCOIN__TESTNET,
            "sepolia" to Chain.ETHEREUM__SEPOLIA,
            "sepolia-testnet" to Chain.ETHEREUM__SEPOLIA,
            "ethereum-holesky" to Chain.ETHEREUM__HOLESKY,
            "optimism-testnet" to Chain.OPTIMISM__GOERLI,
            "arbitrum-testnet" to Chain.ARBITRUM__GOERLI,
            "arbitrum-nova" to Chain.ARBITRUM_NOVA__MAINNET,
            "polygon-zkevm" to Chain.POLYGON_ZKEVM__MAINNET,
            "polygon-zkevm-testnet" to Chain.POLYGON_ZKEVM__TESTNET,
            "zksync" to Chain.ZKSYNC__MAINNET,
            "zksync-testnet" to Chain.ZKSYNC__TESTNET,
            "polygon-mumbai" to Chain.POLYGON_POS__MUMBAI,
            "base" to Chain.BASE__MAINNET,
            "base-goerli" to Chain.BASE__GOERLI,
            "linea" to Chain.LINEA__MAINNET,
            "linea-goerli" to Chain.LINEA__GOERLI,
            "fantom" to Chain.FANTOM__MAINNET,
            "fantom-testnet" to Chain.FANTOM__TESTNET,
            "gnosis" to Chain.GNOSIS__MAINNET,
            "gnosis-chiado" to Chain.GNOSIS__CHIADO,
            "avalanche" to Chain.AVALANCHE__MAINNET,
            "avalanche-fuji" to Chain.AVALANCHE__FUJI,
            // "starknet" to Chain.CHAIN_STARKNET__MAINNET,
            // "starknet-goerli" to Chain.CHAIN_STARKNET__GOERLI,
            // "starknet-goerli2" to Chain.CHAIN_STARKNET__GOERLI2,
            "aurora" to Chain.AURORA__MAINNET,
            "aurora-testnet" to Chain.AURORA__TESTNET,
            // "scroll" to Chain.CHAIN_SCROLL__MAINNET,
            "scroll-alphanet" to Chain.SCROLL__ALPHANET,
            "scroll-sepolia" to Chain.SCROLL__SEPOLIA,
            "mantle" to Chain.MANTLE__MAINNET,
            "mantle-testnet" to Chain.MANTLE__TESTNET,
            "klaytn" to Chain.KLAYTN__MAINNET,
            "klaytn-baobab" to Chain.KLAYTN__BAOBAB,
            "celo" to Chain.CELO__MAINNET,
            "celo-alfajores" to Chain.CELO__ALFAJORES,
            "moonriver" to Chain.MOONBEAM__MOONRIVER,
            "moonbeam" to Chain.MOONBEAM__MAINNET,
            "moonbase-alpha" to Chain.MOONBEAM__ALPHA
        )

        fun chainById(id: String?): Chain {
            if (id == null) {
                return Chain.UNSPECIFIED
            }
            return chainNames[
                id.lowercase(Locale.getDefault()).replace("_", "-").trim()
            ] ?: Chain.UNSPECIFIED
        }

        @JvmStatic
        val objectMapper: ObjectMapper = createObjectMapper()

        var version: String = "DEV"

        val control: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

        private fun createObjectMapper(): ObjectMapper {
            val module = SimpleModule("EmeraldDshackle", Version(1, 0, 0, null, null, null))
            module.addSerializer(JsonRpcResponse::class.java, JsonRpcResponse.ResponseJsonSerializer())
            module.addSerializer(TransactionId::class.java, TransactionIdSerializer())

            module.addDeserializer(EsploraUnspent::class.java, EsploraUnspentDeserializer())
            module.addDeserializer(RpcUnspent::class.java, RpcUnspentDeserializer())
            module.addDeserializer(JsonRpcRequest::class.java, JsonRpcRequest.Deserializer())

            val objectMapper = ObjectMapper()
            objectMapper.registerModule(module)
            objectMapper.registerModule(Jdk8Module())
            objectMapper.registerModule(JavaTimeModule())
            objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            objectMapper
                .setDateFormat(SimpleDateFormat("yyyy-MM-dd\'T\'HH:mm:ss.SSS"))
                .setTimeZone(TimeZone.getTimeZone("UTC"))

            return objectMapper
        }
    }
}
