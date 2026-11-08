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

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.pointed
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.calloc
import platform.posix.memcpy
import platform.posix.memmove
import platform.posix.memset

actual object Memory {
    actual fun getByte(address: Long): Byte = address.toCPointer<ByteVar>()!!.pointed.value
    actual fun putByte(address: Long, value: Byte) {
        address.toCPointer<ByteVar>()!!.pointed.value = value
    }

    actual fun getShort(address: Long): Short = address.toCPointer<ShortVar>()!!.pointed.value
    actual fun putShort(address: Long, value: Short) {
        address.toCPointer<ShortVar>()!!.pointed.value = value
    }

    actual fun getInt(address: Long): Int = address.toCPointer<IntVar>()!!.pointed.value
    actual fun putInt(address: Long, value: Int) {
        address.toCPointer<IntVar>()!!.pointed.value = value
    }

    actual fun getLong(address: Long): Long = address.toCPointer<LongVar>()!!.pointed.value
    actual fun putLong(address: Long, value: Long) {
        address.toCPointer<LongVar>()!!.pointed.value = value
    }

    actual fun getFloat(address: Long): Float = address.toCPointer<FloatVar>()!!.pointed.value
    actual fun putFloat(address: Long, value: Float) {
        address.toCPointer<FloatVar>()!!.pointed.value = value
    }

    actual fun getDouble(address: Long): Double = address.toCPointer<DoubleVar>()!!.pointed.value
    actual fun putDouble(address: Long, value: Double) {
        address.toCPointer<DoubleVar>()!!.pointed.value = value
    }

    actual fun allocate(size: Long): Long {
        val address = calloc(1u, size.toULong()).toLong()
        if (address == 0L) throw OutOfMemoryError("Could not allocate $size native bytes")
        return address
    }

    actual fun free(address: Long) = platform.posix.free(address.toCPointer<ByteVar>())

    actual fun copy(from: Long, to: Long, size: Long) {
        memmove(to.toCPointer<ByteVar>(), from.toCPointer<ByteVar>(), size.toULong())
    }

    actual fun fill(address: Long, size: Long, value: Byte) {
        memset(address.toCPointer<ByteVar>(), value.toInt(), size.toULong())
    }

    actual fun getBytes(address: Long, into: ByteArray, offset: Int, length: Int) {
        if (length == 0) return
        into.usePinned { memcpy(it.addressOf(offset), address.toCPointer<ByteVar>(), length.toULong()) }
    }

    actual fun putBytes(address: Long, from: ByteArray, offset: Int, length: Int) {
        if (length == 0) return
        from.usePinned { memcpy(address.toCPointer<ByteVar>(), it.addressOf(offset), length.toULong()) }
    }
}
