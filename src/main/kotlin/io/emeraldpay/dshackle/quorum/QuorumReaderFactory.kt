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
package io.emeraldpay.dshackle.quorum

import io.emeraldpay.dshackle.reader.Reader
import io.emeraldpay.dshackle.upstream.ApiSource
import io.emeraldpay.dshackle.upstream.rpcclient.JsonRpcRequest
import io.emeraldpay.dshackle.upstream.signature.ResponseSigner
import org.springframework.cloud.sleuth.Tracer
import java.util.concurrent.atomic.AtomicInteger

interface QuorumReader : Reader<JsonRpcRequest, QuorumRpcReader.Result> {
    fun attempts(): AtomicInteger
}

// creates instance of a Quorum based reader
interface QuorumReaderFactory {

    companion object {
        fun default(): QuorumReaderFactory {
            return Default()
        }
    }

    fun create(apis: ApiSource, quorum: CallQuorum, signer: ResponseSigner?, tracer: Tracer): QuorumReader

    class Default : QuorumReaderFactory {
        override fun create(apis: ApiSource, quorum: CallQuorum, signer: ResponseSigner?, tracer: Tracer): QuorumReader {
            return QuorumRpcReader(apis, quorum, signer, tracer)
        }
    }
}
