package io.emeraldpay.dshackle.reader

import io.emeraldpay.dshackle.quorum.BroadcastQuorum
import io.emeraldpay.dshackle.quorum.CallQuorum
import io.emeraldpay.dshackle.quorum.MaximumValueQuorum
import io.emeraldpay.dshackle.quorum.QuorumRequestReader
import io.emeraldpay.dshackle.reader.RequestReader.Result
import io.emeraldpay.dshackle.upstream.ChainCallError
import io.emeraldpay.dshackle.upstream.ChainException
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.Multistream
import io.emeraldpay.dshackle.upstream.Selector
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.ethereum.rpc.RpcException
import io.emeraldpay.dshackle.upstream.signature.ResponseSigner
import io.emeraldpay.dshackle.upstream.stream.Chunk
import org.springframework.cloud.sleuth.Tracer
import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicInteger

abstract class RequestReader(
    private val signer: ResponseSigner?,
) : Reader<ChainRequest, Result> {
    abstract fun attempts(): AtomicInteger

    protected fun getError(key: ChainRequest, err: Throwable) =
        when (err) {
            is RpcException -> ChainException.from(err)
            is ChainException -> err
            else -> ChainException(
                ChainResponse.NumberId(key.id),
                ChainCallError(-32603, "Unhandled internal error: ${err.javaClass}: ${err.message}"),
            )
        }

    protected fun handleError(
        error: ChainCallError?,
        id: Int,
        upstreamSettingsData: List<Upstream.UpstreamSettingsData>,
    ) = error?.asException(ChainResponse.NumberId(id), upstreamSettingsData)
        ?: ChainException(ChainResponse.NumberId(id), ChainCallError(-32603, "Unhandled Upstream error"), upstreamSettingsData)

    protected fun getSignature(key: ChainRequest, response: ChainResponse, upstreamId: String) =
        response.providedSignature
            ?: if (key.nonce != null) {
                signer?.sign(key.nonce, response.getResult(), upstreamId)
            } else {
                null
            }

    class Result(
        val value: ByteArray,
        val signature: ResponseSigner.Signature?,
        val quorum: Int,
        val resolvedUpstreamData: List<Upstream.UpstreamSettingsData>,
        val stream: Flux<Chunk>?,
    )
}

interface RequestReaderFactory {

    companion object {
        fun default(): RequestReaderFactory {
            return Default()
        }
    }

    fun create(data: ReaderData): RequestReader

    class Default : RequestReaderFactory {
        override fun create(data: ReaderData): RequestReader {
            if (data.quorum is MaximumValueQuorum || data.quorum is BroadcastQuorum) {
                return BroadcastReader(data.multistream.getAll(), data.upstreamFilter.matcher, data.signer, data.quorum, data.tracer)
            }
            val apis = data.multistream.getApiSource(data.upstreamFilter)
            return QuorumRequestReader(apis, data.quorum, data.signer, data.tracer)
        }
    }

    data class ReaderData(
        val multistream: Multistream,
        val upstreamFilter: Selector.UpstreamFilter,
        val quorum: CallQuorum,
        val signer: ResponseSigner?,
        val tracer: Tracer,
    )
}
