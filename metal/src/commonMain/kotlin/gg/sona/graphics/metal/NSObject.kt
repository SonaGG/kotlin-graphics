package gg.sona.graphics.metal

import kotlin.jvm.JvmInline

@JvmInline
value class NSObject(val id: Long) {
    val isNull: Boolean get() = id == 0L
}

class MetalException(message: String) : RuntimeException(message)
