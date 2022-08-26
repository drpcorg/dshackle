package io.emeraldpay.dshackle.upstream.bitcoin

import org.apache.commons.io.FileUtils
import spock.lang.Specification

class BitcoinBlockValidatorSpec extends Specification {
    def extractor = new ExtractBlock()

    def "test validate block hash"() {
        def validator = new BitcoinBlockValidator()
        def path = "src/test/resources/blocks/${file}.json"
        def blockContainer = extractor.extract(FileUtils.readFileToByteArray(new File(path)))

        expect:
        validator.isValid(null, blockContainer) == expect

        where:
        file              || expect
        "btc_block_valid" || true
        "btc_block_sec_1" || true
        "btc_block_sec_2" || true
    }

    def "test chainwork"() {
        def validator = new BitcoinBlockValidator()
        def prevPath = "src/test/resources/blocks/${prev}.json"
        def prevBlock = extractor.extract(FileUtils.readFileToByteArray(new File(prevPath)))

        def nextPath = "src/test/resources/blocks/${next}.json"
        def nextBlock = extractor.extract(FileUtils.readFileToByteArray(new File(nextPath)))

        expect:
        validator.isValid(prevBlock, nextBlock) == expect

        where:
        prev              || next              || expect
        "btc_block_sec_1" || "btc_block_sec_2" || true
        "btc_block_sec_2" || "btc_block_sec_1" || false
    }
}
