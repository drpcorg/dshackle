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

import io.emeraldpay.dshackle.reader.Reader
import io.emeraldpay.dshackle.test.TestingCommons
import io.emeraldpay.dshackle.upstream.rpcclient.JsonRpcRequest
import io.emeraldpay.dshackle.upstream.rpcclient.JsonRpcResponse
import org.apache.commons.io.FileUtils
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import spock.lang.Specification

import java.time.Duration

class BitcoinRpcHeadSpec extends Specification {

    def "Follow 2 blocks created over 3 requests"() {
        setup:
        def block1File = new File("src/test/resources/blocks/btc_block_sec_1.json")
        def block2File = new File("src/test/resources/blocks/btc_block_sec_2.json")

        def block1 = FileUtils.readFileToByteArray(block1File)
        def block2 = FileUtils.readFileToByteArray(block2File)

        String hash1 = "00000000000000000009d7e8204534429da6f21d9acd9fc4ac6ccdd23ed57c8e"
        String hash2 = "000000000000000000001566a2ea58b1de20768741717e0cfe742883693f2d44"

        def api = Mock(Reader) {
            _ * read(new JsonRpcRequest("getbestblockhash", [])) >>> [
                    Mono.just(new JsonRpcResponse("\"$hash1\"".bytes, null)),
                    Mono.just(new JsonRpcResponse("\"$hash1\"".bytes, null)),
                    Mono.just(new JsonRpcResponse("\"$hash2\"".bytes, null))
            ]
            _ * read(new JsonRpcRequest("getblock", [hash1])) >> Mono.just(new JsonRpcResponse(block1, null))
            _ * read(new JsonRpcRequest("getblock", [hash2])) >> Mono.just(new JsonRpcResponse(block2, null))
        }
        BitcoinRpcHead head = new BitcoinRpcHead(api, new ExtractBlock(), Duration.ofMillis(200))

        when:
        def act = head.flux.take(2)
        head.start()

        then:
        StepVerifier.create(act)
                .expectNextMatches { block ->
                    block.hash.toHex() == hash1
                }.as("Block 1")
                .expectNextMatches { block ->
                    block.hash.toHex() == hash2
                }.as("Block 2")
                .expectComplete()
                .verify(Duration.ofSeconds(3))

    }
}
