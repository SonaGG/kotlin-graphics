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
package gg.sona.graphics.vulkan

import gg.sona.graphics.runtime.memoryStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstanceTest {
    @Test
    fun createsADeviceAndABuffer() = memoryStack {
        Vulkan.load()
        assertTrue(Vulkan.instanceVersion.minor >= 1, "Vulkan ${Vulkan.instanceVersion}")

        val instance = Vulkan.createInstance {
            applicationInfo {
                applicationName = "kotlin-graphics"
                apiVersion = VkApiVersion.V1_3
            }
        }
        val gpus = instance.enumeratePhysicalDevices()
        assertTrue(gpus.isNotEmpty(), "No physical device")
        for (gpu in gpus) {
            val properties = gpu.getProperties()
            println("${properties.deviceName}: ${properties.deviceType}, Vulkan ${properties.apiVersion}")
        }

        val gpu = gpus.first()
        val family = gpu.getQueueFamilyProperties().indexOfFirst { VkQueueFlags.Graphics in it.queueFlags }
        val device = gpu.createDevice {
            queueCreateInfos(1) {
                queueFamilyIndex = family
                queuePriorities = floatArrayOf(1f)
            }
        }
        val queue = device.getQueue(family, 0)
        val buffer = device.createBuffer {
            size = 1024
            usage = VkBufferUsageFlags.VertexBuffer or VkBufferUsageFlags.TransferDst
            sharingMode = VkSharingMode.Exclusive
        }
        val requirements = device.getBufferMemoryRequirements(buffer)
        assertTrue(requirements.size >= 1024)

        queue.waitIdle()
        device.destroy(buffer)
        device.destroy()
        instance.destroy()
        assertEquals("VertexBuffer", VkBufferUsageFlags.VertexBuffer.toString())
    }
}
