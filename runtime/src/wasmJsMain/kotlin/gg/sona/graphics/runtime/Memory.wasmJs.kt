/*
 * Copyright 2026 Sona Interactive, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package gg.sona.graphics.runtime

/**
 * Web has no addressable native memory, so addresses index one growable little-endian heap.
 * Freed blocks return to power-of-two size classes; address 0 stays reserved for null.
 */
actual object Memory {
    private var heap = ByteArray(1 shl 20)
    private var top = ALIGNMENT
    private val sizes = HashMap<Long, Int>()
    private val free = Array(48) { ArrayList<Long>() }

    /** The bytes the heap has handed out so far, freed blocks included. */
    val usedBytes: Long get() = top

    actual fun getByte(address: Long): Byte = heap[address.toInt()]

    actual fun putByte(address: Long, value: Byte) {
        heap[address.toInt()] = value
    }

    actual fun getShort(address: Long): Short {
        val at = address.toInt()
        return ((heap[at].toInt() and 0xFF) or (heap[at + 1].toInt() shl 8)).toShort()
    }

    actual fun putShort(address: Long, value: Short) {
        val at = address.toInt()
        heap[at] = value.toByte()
        heap[at + 1] = (value.toInt() shr 8).toByte()
    }

    actual fun getInt(address: Long): Int {
        val at = address.toInt()
        return (heap[at].toInt() and 0xFF) or ((heap[at + 1].toInt() and 0xFF) shl 8) or
            ((heap[at + 2].toInt() and 0xFF) shl 16) or (heap[at + 3].toInt() shl 24)
    }

    actual fun putInt(address: Long, value: Int) {
        val at = address.toInt()
        heap[at] = value.toByte()
        heap[at + 1] = (value shr 8).toByte()
        heap[at + 2] = (value shr 16).toByte()
        heap[at + 3] = (value shr 24).toByte()
    }

    actual fun getLong(address: Long): Long = (getInt(address).toLong() and 0xFFFFFFFFL) or (getInt(address + 4).toLong() shl 32)

    actual fun putLong(address: Long, value: Long) {
        putInt(address, value.toInt())
        putInt(address + 4, (value shr 32).toInt())
    }

    actual fun getFloat(address: Long): Float = Float.fromBits(getInt(address))

    actual fun putFloat(address: Long, value: Float) = putInt(address, value.toRawBits())

    actual fun getDouble(address: Long): Double = Double.fromBits(getLong(address))

    actual fun putDouble(address: Long, value: Double) = putLong(address, value.toRawBits())

    actual fun allocate(size: Long): Long {
        require(size >= 0) { "cannot allocate $size bytes" }
        val sizeClass = sizeClass(size)
        val bytes = 1L shl sizeClass
        val address = free[sizeClass].removeLastOrNull() ?: bump(bytes)
        sizes[address] = sizeClass
        heap.fill(0, address.toInt(), (address + bytes).toInt())
        return address
    }

    actual fun free(address: Long) {
        if (address == 0L) return
        val sizeClass = sizes.remove(address) ?: throw IllegalArgumentException("address $address was not allocated")
        free[sizeClass] += address
    }

    /** Resizes the block at [address] like C realloc, keeping its leading bytes. */
    fun reallocate(address: Long, size: Long): Long {
        if (address == 0L) return allocate(size)
        val previous = 1L shl (sizes[address] ?: throw IllegalArgumentException("address $address was not allocated"))
        if (size <= previous) return address
        val moved = allocate(size)
        copy(address, moved, previous)
        free(address)
        return moved
    }

    actual fun copy(from: Long, to: Long, size: Long) {
        heap.copyInto(heap, to.toInt(), from.toInt(), (from + size).toInt())
    }

    actual fun fill(address: Long, size: Long, value: Byte) {
        heap.fill(value, address.toInt(), (address + size).toInt())
    }

    actual fun getBytes(address: Long, into: ByteArray, offset: Int, length: Int) {
        heap.copyInto(into, offset, address.toInt(), address.toInt() + length)
    }

    actual fun putBytes(address: Long, from: ByteArray, offset: Int, length: Int) {
        from.copyInto(heap, address.toInt(), offset, offset + length)
    }

    private fun sizeClass(size: Long): Int {
        var sizeClass = 4
        while ((1L shl sizeClass) < size) sizeClass++
        return sizeClass
    }

    private fun bump(bytes: Long): Long {
        val address = top
        top += bytes
        check(top <= Int.MAX_VALUE) { "the web heap cannot grow past 2 GiB" }
        if (top > heap.size) heap = heap.copyOf(maxOf(top.toInt(), heap.size * 2).coerceAtMost(Int.MAX_VALUE - 8))
        return address
    }

    private const val ALIGNMENT = 16L
}
