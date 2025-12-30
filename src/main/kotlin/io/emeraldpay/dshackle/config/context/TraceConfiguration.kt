package io.emeraldpay.dshackle.config.context

import brave.Tracing
import brave.context.slf4j.MDCScopeDecorator
import brave.grpc.GrpcTracing
import brave.propagation.CurrentTraceContext
import brave.rpc.RpcTracing
import brave.sampler.Sampler
import io.grpc.ServerInterceptor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
open class TraceConfiguration {
    @Bean
    open fun defaultSampler(): Sampler = Sampler.ALWAYS_SAMPLE

    @Bean
    open fun braveTracing(defaultSampler: Sampler): Tracing {
        val currentTraceContext =
            CurrentTraceContext.Default.newBuilder()
                .addScopeDecorator(MDCScopeDecorator.newBuilder().build())
                .build()

        return Tracing.newBuilder()
            .localServiceName("grpc-server")
            .currentTraceContext(currentTraceContext)
            .build()
    }

    @Bean
    open fun rpcTracing(tracing: Tracing): RpcTracing = RpcTracing.create(tracing)

    @Bean
    open fun grpcTracing(rpcTracing: RpcTracing): GrpcTracing = GrpcTracing.create(rpcTracing)

    @Bean
    open fun grpcServerBraveInterceptor(grpcTracing: GrpcTracing): ServerInterceptor = grpcTracing.newServerInterceptor()
}
