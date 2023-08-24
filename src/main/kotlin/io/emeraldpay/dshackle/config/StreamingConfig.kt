package io.emeraldpay.dshackle.config

const val DEFAULT_CHUNK_SIZE = 1048576 // default is 1 MB

data class StreamingConfig(
    val chunkSize: Int
) {

    companion object {
        fun default() = StreamingConfig(DEFAULT_CHUNK_SIZE)
    }
}
