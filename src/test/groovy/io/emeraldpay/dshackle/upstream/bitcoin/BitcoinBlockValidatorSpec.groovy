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

import org.apache.commons.io.FileUtils
import spock.lang.Specification

import java.nio.charset.Charset

class BitcoinBlockValidatorSpec extends Specification {
    def validator = new BitcoinBlockValidator()
    def extractor = new ExtractBlock()

    def "test validate block hash"() {
        def path = "src/test/resources/blocks/${file}.json"

        def blockContainer = extractor.extract(FileUtils.readFileToByteArray(new File(path)))

        expect:
        validator.isValid(null, blockContainer) == expect

        where:
        file          || expect
        "btc_block_valid" || true
        "btc_block_sec_1" || true
        "btc_block_sec_2" || true
    }
}
