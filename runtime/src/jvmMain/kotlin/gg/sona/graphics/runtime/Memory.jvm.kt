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

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_DOUBLE_UNALIGNED
import java.lang.foreign.ValueLayout.JAVA_FLOAT_UNALIGNED
import java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED
import java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED
import java.lang.invoke.MethodHandle

actual object Memory {
    private val all: MemorySegment = MemorySegment.NULL.reinterpret(Long.MAX_VALUE)

    private val calloc: MethodHandle = libc("calloc", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG))
    private val release: MethodHandle = libc("free", FunctionDescriptor.ofVoid(JAVA_LONG))

    private fun libc(name: String, descriptor: FunctionDescriptor): MethodHandle {
        val linker = Linker.nativeLinker()
        return linker.downcallHandle(linker.defaultLookup().find(name).orElseThrow(), descriptor)
    }

    actual fun getByte(address: Long): Byte = all.get(JAVA_BYTE, address)
    actual fun putByte(address: Long, value: Byte) = all.set(JAVA_BYTE, address, value)
    actual fun getShort(address: Long): Short = all.get(JAVA_SHORT_UNALIGNED, address)
    actual fun putShort(address: Long, value: Short) = all.set(JAVA_SHORT_UNALIGNED, address, value)
    actual fun getInt(address: Long): Int = all.get(JAVA_INT_UNALIGNED, address)
    actual fun putInt(address: Long, value: Int) = all.set(JAVA_INT_UNALIGNED, address, value)
    actual fun getLong(address: Long): Long = all.get(JAVA_LONG_UNALIGNED, address)
    actual fun putLong(address: Long, value: Long) = all.set(JAVA_LONG_UNALIGNED, address, value)
    actual fun getFloat(address: Long): Float = all.get(JAVA_FLOAT_UNALIGNED, address)
    actual fun putFloat(address: Long, value: Float) = all.set(JAVA_FLOAT_UNALIGNED, address, value)
    actual fun getDouble(address: Long): Double = all.get(JAVA_DOUBLE_UNALIGNED, address)
    actual fun putDouble(address: Long, value: Double) = all.set(JAVA_DOUBLE_UNALIGNED, address, value)

    actual fun allocate(size: Long): Long {
        val address = calloc.invokeExact(1L, size) as Long
        if (address == 0L) throw OutOfMemoryError("Could not allocate $size native bytes")
        return address
    }

    actual fun free(address: Long) {
        release.invokeExact(address)
    }

    actual fun copy(from: Long, to: Long, size: Long) = MemorySegment.copy(all, from, all, to, size)
    actual fun fill(address: Long, size: Long, value: Byte) {
        all.asSlice(address, size).fill(value)
    }

    actual fun getBytes(address: Long, into: ByteArray, offset: Int, length: Int) =
        MemorySegment.copy(all, JAVA_BYTE, address, into, offset, length)

    actual fun putBytes(address: Long, from: ByteArray, offset: Int, length: Int) =
        MemorySegment.copy(from, offset, all, JAVA_BYTE, address, length)
}
