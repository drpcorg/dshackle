package io.emeraldpay.dshackle.rpc

import io.emeraldpay.api.proto.BlockchainOuterClass
import io.emeraldpay.api.proto.BlockchainOuterClass.NodeDescription
import io.emeraldpay.api.proto.BlockchainOuterClass.NodeStatus
import io.emeraldpay.api.proto.BlockchainOuterClass.NodeStatusResponse
import io.emeraldpay.api.proto.BlockchainOuterClass.SubscribeNodeStatusRequest
import io.emeraldpay.api.proto.Common
import io.emeraldpay.dshackle.upstream.CurrentMultistreamHolder
import io.emeraldpay.dshackle.upstream.Multistream
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.UpstreamAvailability
import io.emeraldpay.dshackle.upstream.grpc.GrpcUpstream
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.util.concurrent.ConcurrentHashMap

@Service
class SubscribeNodeStatus(
    private val multistreams: CurrentMultistreamHolder
) {

    companion object {
        private val log = LoggerFactory.getLogger(SubscribeNodeStatus::class.java)
    }

    fun subscribe(req: SubscribeNodeStatusRequest): Flux<NodeStatusResponse> {
        val knownUpstreams = ConcurrentHashMap<String, Sinks.Many<Boolean>>()
        // subscribe on head/status updates for known upstreams
        val upstreamUpdates = Flux.merge(
            multistreams.all()
                .flatMap { ms ->
                    ms.getAll().map { up ->
                        knownUpstreams[up.getId()] = Sinks.many().multicast().directBestEffort()
                        subscribeUpstreamUpdates(ms, up, knownUpstreams[up.getId()]!!)
                    }
                }
        )
        // stop removed upstreams update fluxes
        val removals = Flux.merge(
            multistreams.all()
                .map { ms ->
                    ms.subscribeRemovedUpstreams().mapNotNull { up ->
                        knownUpstreams[up.getId()]?.let {
                            val result = it.tryEmitNext(true)
                            if (result.isFailure) {
                                log.warn("Unable to emit event about removal of an upstream - $result")
                            }
                            knownUpstreams.remove(up.getId())
                            val nodeStatusRespBuilder = NodeStatusResponse.newBuilder()
                                .setNodeId(up.getId())
                                .setDescription(buildDescription(ms, up))
                                .setStatus(
                                    buildStatus(
                                        UpstreamAvailability.UNAVAILABLE,
                                        up.getHead().getCurrentHeight()
                                    )
                                )
                            (up as? GrpcUpstream)?.let { grpcUp ->
                                grpcUp.getBuildInfo().version?.let { version ->
                                    nodeStatusRespBuilder.nodeBuildInfoBuilder.setVersion(version)
                                }
                            }
                            nodeStatusRespBuilder.build()
                        }
                    }
                }
        )

        // subscribe on head/status updates for just added upstreams
        val multiStreamUpdates = Flux.merge(
            multistreams.all()
                .map { ms ->
                    ms.subscribeAddedUpstreams()
                        .distinctUntilChanged {
                            it.getId()
                        }
                        .filter {
                            !knownUpstreams.contains(it.getId())
                        }
                        .flatMap { up ->
                            knownUpstreams[up.getId()] = Sinks.many().multicast().directBestEffort()
                            val nodeStatusRespBuilder = NodeStatusResponse.newBuilder()
                                .setNodeId(up.getId())
                                .setDescription(buildDescription(ms, up))
                                .setStatus(buildStatus(up.getStatus(), up.getHead().getCurrentHeight()))
                            (up as? GrpcUpstream)?.let { grpcUp ->
                                grpcUp.getBuildInfo().version?.let { version ->
                                    nodeStatusRespBuilder.nodeBuildInfoBuilder.setVersion(version)
                                }
                            }
                            Flux.concat(
                                Mono.just(nodeStatusRespBuilder.build()),
                                subscribeUpstreamUpdates(ms, up, knownUpstreams[up.getId()]!!)
                            )
                        }
                }
        )

        return Flux.merge(upstreamUpdates, multiStreamUpdates, removals)
    }

    private fun subscribeUpstreamUpdates(
        ms: Multistream,
        upstream: Upstream,
        cancel: Sinks.Many<Boolean>
    ): Flux<NodeStatusResponse> {
        val version = (upstream as? GrpcUpstream)?.let { grpcUp ->
            grpcUp.getBuildInfo().version
        }
        val statuses = upstream.observeStatus()
            .takeUntilOther(cancel.asFlux())
            .map {
                val nodeStatusRespBuilder = NodeStatusResponse.newBuilder()
                    .setNodeId(upstream.getId())
                    .setStatus(buildStatus(it, upstream.getHead().getCurrentHeight()))
                    .setDescription(buildDescription(ms, upstream))
                version?.let { v -> nodeStatusRespBuilder.nodeBuildInfoBuilder.setVersion(v) }
                nodeStatusRespBuilder.build()
            }
        val nodeStatusRespBuilder = NodeStatusResponse.newBuilder()
            .setNodeId(upstream.getId())
            .setDescription(buildDescription(ms, upstream))
            .setStatus(buildStatus(upstream.getStatus(), upstream.getHead().getCurrentHeight()))
        version?.let { v -> nodeStatusRespBuilder.nodeBuildInfoBuilder.setVersion(v) }
        val currentState = Mono.just(nodeStatusRespBuilder.build())
        return Flux.concat(currentState, statuses)
    }

    private fun buildDescription(ms: Multistream, up: Upstream): NodeDescription.Builder =
        NodeDescription.newBuilder()
            .setChain(Common.ChainRef.forNumber(ms.chain.id))
            .setNodeId(up.nodeId().toInt())
            .addAllNodeLabels(
                up.getLabels().map { nodeLabels ->
                    BlockchainOuterClass.NodeLabels.newBuilder()
                        .addAllLabels(
                            nodeLabels.map {
                                BlockchainOuterClass.Label.newBuilder()
                                    .setName(it.key)
                                    .setValue(it.value)
                                    .build()
                            }
                        )
                        .build()
                }
            )
            .addAllSupportedSubscriptions(ms.getEgressSubscription().getAvailableTopics())
            .addAllSupportedMethods(up.getMethods().getSupportedMethods())

    private fun buildStatus(status: UpstreamAvailability, height: Long?): NodeStatus.Builder =
        NodeStatus.newBuilder()
            .setAvailability(BlockchainOuterClass.AvailabilityEnum.forNumber(status.grpcId))
            .setCurrentHeight(height ?: 0)
}
