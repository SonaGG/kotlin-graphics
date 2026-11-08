package gg.sona.graphics.runtime

private val stacks = ThreadLocal.withInitial { MemoryStack() }

actual fun currentMemoryStack(): MemoryStack = stacks.get()
