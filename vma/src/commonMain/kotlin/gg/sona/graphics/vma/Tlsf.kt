package gg.sona.graphics.vma

/** A two-level segregated fit allocator over one block of [size] bytes. Allocation and release take constant time. */
internal class Tlsf(val size: Long) {
    internal class Node(var offset: Long, var size: Long) {
        var free = true
        var previous: Node? = null
        var next: Node? = null
        var previousFree: Node? = null
        var nextFree: Node? = null
    }

    private val firstBits = LongArray(1)
    private val secondBits = IntArray(FIRST_LEVELS)
    private val heads = arrayOfNulls<Node>(FIRST_LEVELS * SECOND_LEVELS)
    private var allocations = 0

    val isEmpty: Boolean get() = allocations == 0

    init {
        insert(Node(0, size))
    }

    fun allocate(size: Long, alignment: Long): Node? {
        val request = maxOf(size, 1L)
        val (first, second) = mapping(roundUp(request + alignment - 1))
        val candidate = find(first, second) ?: mapping(request).let { (f, s) -> scan(f, s, request, alignment) }
        val node = candidate ?: return null
        remove(node)
        val start = (node.offset + alignment - 1) / alignment * alignment
        if (start > node.offset) {
            val padding = Node(node.offset, start - node.offset)
            link(node.previous, padding, node)
            node.offset = start
            node.size -= padding.size
            insert(padding)
        }
        if (node.size > request) {
            val rest = Node(node.offset + request, node.size - request)
            link(node, rest, node.next)
            node.size = request
            insert(rest)
        }
        node.free = false
        allocations++
        return node
    }

    fun free(node: Node) {
        node.free = true
        allocations--
        var merged = node
        merged.previous?.takeIf { it.free }?.let { previous ->
            remove(previous)
            previous.size += merged.size
            previous.next = merged.next
            merged.next?.previous = previous
            merged = previous
        }
        merged.next?.takeIf { it.free }?.let { next ->
            remove(next)
            merged.size += next.size
            merged.next = next.next
            next.next?.previous = merged
        }
        insert(merged)
    }

    private fun link(previous: Node?, node: Node, next: Node?) {
        node.previous = previous
        node.next = next
        previous?.next = node
        next?.previous = node
    }

    private fun roundUp(size: Long): Long =
        if (size < SMALL) size + SMALL / SECOND_LEVELS - 1 else size + (1L shl (63 - size.countLeadingZeroBits() - SECOND_BITS)) - 1

    private fun mapping(size: Long): Pair<Int, Int> {
        if (size < SMALL) return 0 to (size / (SMALL / SECOND_LEVELS)).toInt()
        val first = 63 - size.countLeadingZeroBits()
        val second = ((size ushr (first - SECOND_BITS)) - SECOND_LEVELS).toInt()
        return (first - SMALL_BITS + 1) to second
    }

    private fun insert(node: Node) {
        val (first, second) = mapping(node.size)
        val index = first * SECOND_LEVELS + second
        node.previousFree = null
        node.nextFree = heads[index]
        heads[index]?.previousFree = node
        heads[index] = node
        secondBits[first] = secondBits[first] or (1 shl second)
        firstBits[0] = firstBits[0] or (1L shl first)
    }

    private fun remove(node: Node) {
        val (first, second) = mapping(node.size)
        val index = first * SECOND_LEVELS + second
        node.previousFree?.nextFree = node.nextFree
        node.nextFree?.previousFree = node.previousFree
        if (heads[index] === node) heads[index] = node.nextFree
        node.previousFree = null
        node.nextFree = null
        if (heads[index] == null) {
            secondBits[first] = secondBits[first] and (1 shl second).inv()
            if (secondBits[first] == 0) firstBits[0] = firstBits[0] and (1L shl first).inv()
        }
    }

    private fun find(first: Int, second: Int): Node? {
        if (first >= FIRST_LEVELS) return null
        var map = secondBits[first] and (-1 shl second)
        var level = first
        if (map == 0) {
            val higher = firstBits[0] and (-1L shl (first + 1))
            if (higher == 0L) return null
            level = higher.countTrailingZeroBits()
            map = secondBits[level]
        }
        return heads[level * SECOND_LEVELS + map.countTrailingZeroBits()]
    }

    private fun scan(first: Int, second: Int, size: Long, alignment: Long): Node? {
        if (first >= FIRST_LEVELS) return null
        var node = heads[first * SECOND_LEVELS + second]
        while (node != null) {
            val start = (node.offset + alignment - 1) / alignment * alignment
            if (start + size <= node.offset + node.size) return node
            node = node.nextFree
        }
        return null
    }

    private companion object {
        const val SECOND_BITS = 5
        const val SECOND_LEVELS = 1 shl SECOND_BITS
        const val SMALL_BITS = 7
        const val SMALL = 1L shl SMALL_BITS
        const val FIRST_LEVELS = 64 - SMALL_BITS + 1
    }
}
