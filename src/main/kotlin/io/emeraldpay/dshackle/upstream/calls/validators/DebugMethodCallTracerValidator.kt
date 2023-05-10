package io.emeraldpay.dshackle.upstream.calls.validators

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.exc.ValueInstantiationException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Component

@Component
class DebugMethodCallTracerValidator : EthereumMethodValidator {
    private val methods = setOf(
        "debug_traceBlockByNumber",
        "debug_traceBlockByHash",
        "debug_traceBlock",
        "debug_traceTransaction"
    )
    private val mapper = jacksonObjectMapper()

    override fun support(method: String): Boolean = methods.contains(method)

    override fun validate(method: String, params: List<Any>) {
        if (params.size != 2) {
            throw IllegalStateException("There must be 2 params for $method")
        }
        try {
            mapper.readValue<Tracer>(mapper.writeValueAsString(params[1]))
        } catch (e: ValueInstantiationException) {
            throw IllegalStateException("${e.cause!!.message} for $method")
        } catch (e: JacksonException) {
            throw IllegalStateException("The second param must be a tracer object for $method")
        }
    }
}

data class Tracer(
    val tracer: String?
) {
    companion object {
        private val possibleTracers = setOf("callTracer", "prestateTracer")
    }

    init {
        if (!possibleTracers.contains(tracer)) {
            throw IllegalStateException(
                "Invalid tracer value $tracer. Possible values - [callTracer, prestateTracer]"
            )
        }
    }
}
