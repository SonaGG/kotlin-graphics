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
package gg.sona.graphics.vma

import gg.sona.graphics.vulkan.VkDeviceMemory
import gg.sona.graphics.vulkan.VkMemoryPropertyFlags
import kotlin.jvm.JvmInline

/** Tells the allocator which kind of memory an allocation needs. */
@JvmInline
value class VmaMemoryUsage(val value: Int) {
    companion object {
        /** Uses only the required and preferred flags of the create info. */
        val Unknown = VmaMemoryUsage(0)

        /** Requires lazily allocated memory, such as a transient attachment on a tiled GPU. */
        val GpuLazilyAllocated = VmaMemoryUsage(6)

        /** Selects the memory type from the usage of the buffer or image and the host access flags. */
        val Auto = VmaMemoryUsage(7)

        /** Same as [Auto], but prefers device-local memory. */
        val AutoPreferDevice = VmaMemoryUsage(8)

        /** Same as [Auto], but prefers memory that is not device local. */
        val AutoPreferHost = VmaMemoryUsage(9)
    }
}

/** Flags for one allocation. */
@JvmInline
value class VmaAllocationCreateFlags(val value: Int) {
    infix fun or(other: VmaAllocationCreateFlags) = VmaAllocationCreateFlags(value or other.value)
    operator fun contains(other: VmaAllocationCreateFlags) = value and other.value == other.value

    companion object {
        val None = VmaAllocationCreateFlags(0)

        /** Gives the allocation its own `VkDeviceMemory`. */
        val DedicatedMemory = VmaAllocationCreateFlags(0x1)

        /** Fails instead of allocating a new `VkDeviceMemory` block. */
        val NeverAllocate = VmaAllocationCreateFlags(0x2)

        /** Keeps the allocation mapped. [VmaAllocation.mappedData] gives the address. */
        val Mapped = VmaAllocationCreateFlags(0x4)

        /** The host writes the memory in sequence and does not read it. */
        val HostAccessSequentialWrite = VmaAllocationCreateFlags(0x400)

        /** The host reads and writes the memory in any order. */
        val HostAccessRandom = VmaAllocationCreateFlags(0x800)

        /** Lets the allocator select device-local memory that is not host visible. The caller then copies through a staging buffer. */
        val HostAccessAllowTransferInstead = VmaAllocationCreateFlags(0x1000)
    }
}

/** Flags for the allocator. */
@JvmInline
value class VmaAllocatorCreateFlags(val value: Int) {
    infix fun or(other: VmaAllocatorCreateFlags) = VmaAllocatorCreateFlags(value or other.value)
    operator fun contains(other: VmaAllocatorCreateFlags) = value and other.value == other.value

    companion object {
        val None = VmaAllocatorCreateFlags(0)

        /** Allocates all memory with `VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT`. Enable it when buffers use device addresses. */
        val BufferDeviceAddress = VmaAllocatorCreateFlags(0x20)
    }
}

/** The parameters of one allocation. */
class VmaAllocationCreateInfo(
    val flags: VmaAllocationCreateFlags = VmaAllocationCreateFlags.None,
    val usage: VmaMemoryUsage = VmaMemoryUsage.Auto,
    val requiredFlags: VkMemoryPropertyFlags = VkMemoryPropertyFlags.None,
    val preferredFlags: VkMemoryPropertyFlags = VkMemoryPropertyFlags.None,
    val memoryTypeBits: Int = 0,
)

/** A range of device memory that the allocator gave. Release it with [VmaAllocator.freeMemory]. */
class VmaAllocation internal constructor(
    val memory: VkDeviceMemory,
    val offset: Long,
    val size: Long,
    val memoryTypeIndex: Int,
    internal val block: Block?,
    internal val node: Tlsf.Node?,
    internal val dedicatedMapping: Long,
) {
    /** The host address of the allocation, or 0 when the allocation is not mapped. */
    val mappedData: Long get() = block?.mapped?.let { if (it == 0L) 0L else it + offset } ?: dedicatedMapping

    val isDedicated: Boolean get() = block == null
}

/** A buffer and the allocation that it is bound to. */
class VmaBuffer(val buffer: gg.sona.graphics.vulkan.VkBuffer, val allocation: VmaAllocation)

/** An image and the allocation that it is bound to. */
class VmaImage(val image: gg.sona.graphics.vulkan.VkImage, val allocation: VmaAllocation)

/** Thrown when no memory type fits an allocation, or when the device is out of memory. */
class VmaException(message: String) : RuntimeException(message)
