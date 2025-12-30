/**
 * Copyright (c) 2020 EmeraldPay, Inc
 * Copyright (c) 2020 ETCDEV GmbH
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

import io.emeraldpay.dshackle.config.MainConfig
import io.emeraldpay.dshackle.monitoring.accesslog.AccessHandlerHttp
import io.emeraldpay.dshackle.proxy.ProxyServer
import io.emeraldpay.dshackle.proxy.ReadRpcJson
import io.emeraldpay.dshackle.proxy.WriteRpcJson
import io.emeraldpay.dshackle.rpc.NativeCall
import io.emeraldpay.dshackle.rpc.NativeSubscribe
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Starts HTTP proxy endpoint, if configured
 */
@Service
class ProxyStarter(
    private val mainConfig: MainConfig,
    private val readRpcJson: ReadRpcJson,
    private val writeRpcJson: WriteRpcJson,
    private val nativeCall: NativeCall,
    private val nativeSubscribe: NativeSubscribe,
    private val tlsSetup: TlsSetup,
    private val accessHandlerHttp: AccessHandlerHttp,
) {

    companion object {
        private val log = LoggerFactory.getLogger(ProxyStarter::class.java)
    }

    @PostConstruct
    fun start() {
        val config = mainConfig.proxy
        if (config == null) {
            log.debug("Proxy server is not configured")
            return
        }
        val server = ProxyServer(config, readRpcJson, writeRpcJson, nativeCall, nativeSubscribe, tlsSetup, accessHandlerHttp.factory)
        server.start()
    }
}
