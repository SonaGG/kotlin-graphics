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

expect object Memory {
    fun getByte(address: Long): Byte
    fun putByte(address: Long, value: Byte)
    fun getShort(address: Long): Short
    fun putShort(address: Long, value: Short)
    fun getInt(address: Long): Int
    fun putInt(address: Long, value: Int)
    fun getLong(address: Long): Long
    fun putLong(address: Long, value: Long)
    fun getFloat(address: Long): Float
    fun putFloat(address: Long, value: Float)
    fun getDouble(address: Long): Double
    fun putDouble(address: Long, value: Double)

    fun allocate(size: Long): Long
    fun free(address: Long)

    fun copy(from: Long, to: Long, size: Long)
    fun fill(address: Long, size: Long, value: Byte)
    fun getBytes(address: Long, into: ByteArray, offset: Int = 0, length: Int = into.size - offset)
    fun putBytes(address: Long, from: ByteArray, offset: Int = 0, length: Int = from.size - offset)
}

fun Memory.allocateUtf8(text: String): Long {
    val bytes = text.encodeToByteArray()
    val address = allocate(bytes.size + 1L)
    putBytes(address, bytes)
    return address
}

fun Memory.readUtf8(address: Long, maxLength: Int = Int.MAX_VALUE): String {
    if (address == 0L) return ""
    var length = 0
    while (length < maxLength && getByte(address + length) != 0.toByte()) length++
    val bytes = ByteArray(length)
    getBytes(address, bytes)
    return bytes.decodeToString()
}
