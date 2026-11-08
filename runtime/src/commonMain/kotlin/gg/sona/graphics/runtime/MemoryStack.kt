package gg.sona.graphics.runtime

class MemoryStack(val capacity: Long = DEFAULT_CAPACITY) {
    private val base = Memory.allocate(capacity)

    var position = 0L
        private set

    @PublishedApi
    internal fun reset(position: Long) {
        this.position = position
    }

    fun allocate(size: Long, alignment: Long = 8): Long {
        val start = (position + alignment - 1) and (alignment - 1).inv()
        val end = start + size
        if (end > capacity) throw IllegalStateException("The memory stack has $capacity bytes and cannot give $size more")
        position = end
        val address = base + start
        Memory.fill(address, size, 0)
        return address
    }

    fun utf8(text: String): Long {
        val bytes = text.encodeToByteArray()
        val address = allocate(bytes.size + 1L, 1)
        Memory.putBytes(address, bytes)
        return address
    }

    companion object {
        const val DEFAULT_CAPACITY = 1L shl 20
    }
}

context(stack: MemoryStack)
val stack: MemoryStack get() = stack

expect fun currentMemoryStack(): MemoryStack

inline fun <R> memoryStack(block: context(MemoryStack) () -> R): R {
    val stack = currentMemoryStack()
    val saved = stack.position
    try {
        return block(stack)
    } finally {
        stack.reset(saved)
    }
}
