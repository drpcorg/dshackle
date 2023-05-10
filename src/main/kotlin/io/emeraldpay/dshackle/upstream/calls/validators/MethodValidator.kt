package io.emeraldpay.dshackle.upstream.calls.validators

interface MethodValidator {

    fun support(method: String): Boolean

    fun validate(method: String, params: List<Any>)
}

interface EthereumMethodValidator : MethodValidator
