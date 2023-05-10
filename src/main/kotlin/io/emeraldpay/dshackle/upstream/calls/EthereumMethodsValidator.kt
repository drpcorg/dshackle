package io.emeraldpay.dshackle.upstream.calls

import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.upstream.calls.validators.EthereumMethodValidator
import org.springframework.stereotype.Component

@Component
class EthereumMethodsValidator(
    private val methodValidators: List<EthereumMethodValidator>
) {
    private val mapper = Global.objectMapper

    fun validateMethod(method: String, params: String) {
        val listParams = mapper.readerFor(Any::class.java).readValues<Any>(params).readAll()
        methodValidators
            .filter { it.support(method) }
            .forEach { it.validate(method, listParams) }
    }
}
