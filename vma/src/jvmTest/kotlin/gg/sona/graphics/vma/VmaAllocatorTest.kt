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

import gg.sona.graphics.runtime.Memory
import gg.sona.graphics.runtime.memoryStack
import gg.sona.graphics.vulkan.VkApiVersion
import gg.sona.graphics.vulkan.VkBufferCreateInfo
import gg.sona.graphics.vulkan.VkBufferUsageFlags
import gg.sona.graphics.vulkan.VkMemoryPropertyFlags
import gg.sona.graphics.vulkan.VkQueueFlags
import gg.sona.graphics.vulkan.VkSharingMode
import gg.sona.graphics.vulkan.Vulkan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VmaAllocatorTest {
    @Test
    fun allocatesMappedAndDeviceLocalBuffers() = memoryStack {
        if (runCatching { Vulkan.load() }.isFailure) return@memoryStack
        val instance = runCatching { Vulkan.createInstance { applicationInfo { apiVersion = VkApiVersion.V1_3 } } }.getOrNull() ?: return@memoryStack
        val gpu = instance.enumeratePhysicalDevices().firstOrNull() ?: return@memoryStack instance.destroy()
        val family = gpu.getQueueFamilyProperties().indexOfFirst { VkQueueFlags.Graphics in it.queueFlags }
        val device = gpu.createDevice {
            queueCreateInfos(1) {
                queueFamilyIndex = family
                queuePriorities = floatArrayOf(1f)
            }
        }
        val allocator = VmaAllocator(gpu, device)
        fun info(size: Long, usage: VkBufferUsageFlags) = VkBufferCreateInfo {
            this.size = size
            this.usage = usage
            sharingMode = VkSharingMode.Exclusive
        }

        val staging = allocator.createBuffer(
            info(4096, VkBufferUsageFlags.TransferSrc),
            VmaAllocationCreateInfo(VmaAllocationCreateFlags.Mapped or VmaAllocationCreateFlags.HostAccessSequentialWrite),
        )
        assertTrue(VkMemoryPropertyFlags.HostVisible in allocator.getMemoryProperties(staging.allocation))
        assertNotEquals(0L, staging.allocation.mappedData)
        Memory.putInt(staging.allocation.mappedData, 0x1234)
        allocator.flushAllocation(staging.allocation)
        assertEquals(0x1234, Memory.getInt(staging.allocation.mappedData))

        val vertices = List(16) {
            allocator.createBuffer(info(65536, VkBufferUsageFlags.VertexBuffer or VkBufferUsageFlags.TransferDst), VmaAllocationCreateInfo())
        }
        assertTrue(vertices.all { VkMemoryPropertyFlags.DeviceLocal in allocator.getMemoryProperties(it.allocation) })
        val ranges = vertices.filter { !it.allocation.isDedicated }.groupBy { it.allocation.memory }.values.flatMap { group ->
            group.map { it.allocation.offset until it.allocation.offset + it.allocation.size }.sortedBy { it.first }.zipWithNext()
        }
        assertTrue(ranges.all { (a, b) -> a.last < b.first }, "Suballocations overlap")

        val alias = allocator.createAliasingBuffer(vertices[0].allocation, info(1024, VkBufferUsageFlags.VertexBuffer))
        device.destroy(alias)
        vertices.forEach { allocator.destroyBuffer(it.buffer, it.allocation) }
        val reused = allocator.createBuffer(info(65536, VkBufferUsageFlags.VertexBuffer), VmaAllocationCreateInfo())
        allocator.destroyBuffer(reused.buffer, reused.allocation)
        allocator.destroyBuffer(staging.buffer, staging.allocation)
        allocator.close()
        device.destroy()
        instance.destroy()
    }
}
