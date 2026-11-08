package gg.sona.graphics.runtime

import kotlin.native.concurrent.ThreadLocal

@ThreadLocal
private object Stacks {
    val stack = MemoryStack()
}

actual fun currentMemoryStack(): MemoryStack = Stacks.stack
