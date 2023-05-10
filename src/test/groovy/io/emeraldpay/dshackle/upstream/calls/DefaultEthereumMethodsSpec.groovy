package io.emeraldpay.dshackle.upstream.calls

import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.upstream.calls.validators.DebugMethodCallTracerValidator
import spock.lang.Specification

class DefaultEthereumMethodsSpec extends Specification {
    def validator = new EthereumMethodsValidator(
            [new DebugMethodCallTracerValidator()]
    )

    def "eth_chainId is available"() {
        setup:
        def methods = new DefaultEthereumMethods(Chain.ETHEREUM, validator)
        when:
        def act = methods.isAvailable("eth_chainId")
        then:
        act
    }

    def "eth_chainId is hardcoded"() {
        setup:
        def methods = new DefaultEthereumMethods(Chain.ETHEREUM, validator)
        when:
        def act = methods.isHardcoded("eth_chainId")
        then:
        act
    }

    def "eth_chainId is not callable"() {
        setup:
        def methods = new DefaultEthereumMethods(Chain.ETHEREUM, validator)
        when:
        def act = methods.isCallable("eth_chainId")
        then:
        !act
    }

    def "Provides hardcoded correct chainId"() {
        expect:
        new String(new DefaultEthereumMethods(chain, validator).executeHardcoded("eth_chainId")) == id
        where:
        chain                  | id
        Chain.ETHEREUM         | '"0x1"'
        Chain.ETHEREUM_CLASSIC | '"0x3d"'
        Chain.TESTNET_KOVAN    | '"0x2a"'
        Chain.TESTNET_GOERLI   | '"0x5"'
        Chain.TESTNET_RINKEBY  | '"0x4"'
        Chain.TESTNET_ROPSTEN  | '"0x3"'
    }

    def "Optimism chain unsupported methods"() {
        setup:
        def methods = new DefaultEthereumMethods(Chain.OPTIMISM, validator)
        when:
        def acc = methods.isAvailable("eth_getAccounts")
        def trans = methods.isAvailable("eth_sendTransaction")
        then:
        !acc
        !trans
    }

    def "Has supported specific methods"() {
        expect:
        new DefaultEthereumMethods(chain, validator).getSupportedMethods().containsAll(methods)
        where:
        chain          | methods
        Chain.POLYGON  | ["bor_getAuthor",
                          "bor_getCurrentValidators",
                          "bor_getCurrentProposer",
                          "bor_getRootHash",
                          "bor_getSignersAtHash",
                          "eth_getRootHash"]
        Chain.OPTIMISM | ["rollup_gasPrices"]
    }

    def "Has no filter methods by default"() {
        setup:
        def methods = new DefaultEthereumMethods(Chain.ETHEREUM, validator)
        when:
        def act = methods.getSupportedMethods().findAll { it.containsIgnoreCase("filter") }
        then:
        act.isEmpty()
    }

    def "Has no trace methods by default"() {
        setup:
        def methods = new DefaultEthereumMethods(Chain.ETHEREUM, validator)
        when:
        def act = methods.getSupportedMethods().findAll { it.containsIgnoreCase("trace") }
        then:
        act.isEmpty()
    }

    def "Debug methods are valid"() {
        expect:
        new DefaultEthereumMethods(Chain.ETHEREUM, validator).validateMethod(method, params)
        where:
        method                      | params
        "debug_traceBlockByNumber"  | '["0x1ABE152",  {"tracer": "callTracer"}]'
        "debug_traceBlockByHash"    | '["0x1ABE152",  {"tracer": "callTracer"}]'
        "debug_traceBlock"          | '["0x1ABE152",  {"tracer": "callTracer"}]'
        "debug_traceTransaction"    | '["0x1ABE152",  {"tracer": "callTracer"}]'
        "debug_traceBlockByNumber"  | '["0x1ABE152",  {"tracer": "prestateTracer"}]'
        "debug_traceBlockByHash"    | '["0x1ABE152",  {"tracer": "prestateTracer"}]'
        "debug_traceBlock"          | '["0x1ABE152",  {"tracer": "prestateTracer"}]'
        "debug_traceTransaction"    | '["0x1ABE152",  {"tracer": "prestateTracer"}]'
    }

    def "Debug methods is not valid if there are no 2 params"() {
        setup:
        def methods = new DefaultEthereumMethods(Chain.ETHEREUM, validator)
        when:
        methods.validateMethod("debug_traceBlockByNumber", '["0x1ABE152"]')
        then:
        def e = thrown(IllegalStateException)
        e.message == "There must be 2 params for debug_traceBlockByNumber"
    }

    def "Debug methods is not valid if no correct tracers"() {
        setup:
        def methods = new DefaultEthereumMethods(Chain.ETHEREUM, validator)
        when:
        methods.validateMethod("debug_traceBlockByNumber", '["0x1ABE152", {"tracer": "wrong"}]')
        then:
        def e = thrown(IllegalStateException)
        e.message == "Invalid tracer value wrong. Possible values - [callTracer, prestateTracer] for debug_traceBlockByNumber"
    }

    def "Debug methods is not valid if not correct tracer object"() {
        setup:
        def methods = new DefaultEthereumMethods(Chain.ETHEREUM, validator)
        when:
        methods.validateMethod("debug_traceBlockByNumber", '["0x1ABE152", [""]]]')
        then:
        def e = thrown(IllegalStateException)
        e.message == "The second param must be a tracer object for debug_traceBlockByNumber"
    }
}
