package gg.sona.graphics.vma

import gg.sona.graphics.runtime.MemoryStack
import gg.sona.graphics.runtime.memoryStack
import gg.sona.graphics.vulkan.Vk
import gg.sona.graphics.vulkan.VkBuffer
import gg.sona.graphics.vulkan.VkBufferCreateInfo
import gg.sona.graphics.vulkan.VkBufferMemoryRequirementsInfo2
import gg.sona.graphics.vulkan.VkDevice
import gg.sona.graphics.vulkan.VkDeviceMemory
import gg.sona.graphics.vulkan.VkImage
import gg.sona.graphics.vulkan.VkImageCreateInfo
import gg.sona.graphics.vulkan.VkImageMemoryRequirementsInfo2
import gg.sona.graphics.vulkan.VkMappedMemoryRange
import gg.sona.graphics.vulkan.VkMemoryAllocateFlags
import gg.sona.graphics.vulkan.VkMemoryAllocateFlagsInfo
import gg.sona.graphics.vulkan.VkMemoryAllocateInfo
import gg.sona.graphics.vulkan.VkMemoryDedicatedAllocateInfo
import gg.sona.graphics.vulkan.VkMemoryDedicatedRequirements
import gg.sona.graphics.vulkan.VkMemoryPropertyFlags
import gg.sona.graphics.vulkan.VkMemoryRequirements2
import gg.sona.graphics.vulkan.VkPhysicalDevice
import gg.sona.graphics.vulkan.VkPhysicalDeviceType
import gg.sona.graphics.vulkan.VulkanException
import gg.sona.graphics.vulkan.extend
import kotlin.concurrent.atomics.AtomicBoolean

internal class Block(val memory: VkDeviceMemory, val size: Long, val type: Int) {
    val tlsf = Tlsf(size)
    var mapped = 0L
    var mapCount = 0
}

/**
 * Suballocates Vulkan device memory, in the same way as the Vulkan Memory Allocator.
 * Each memory type has blocks that a two-level segregated fit allocator divides. Large or dedicated resources get their own memory.
 */
class VmaAllocator(
    val physicalDevice: VkPhysicalDevice,
    val device: VkDevice,
    val flags: VmaAllocatorCreateFlags = VmaAllocatorCreateFlags.None,
    val preferredLargeHeapBlockSize: Long = 256L shl 20,
) : AutoCloseable {
    private val lock = AtomicBoolean(false)
    private val typeFlags: Array<VkMemoryPropertyFlags>
    private val typeHeap: IntArray
    private val heapSize: LongArray
    private val integrated: Boolean
    private val atomSize: Long
    private val granularity: Long
    private val blocks: Array<ArrayList<Block>>
    private val dedicated = LinkedHashSet<VmaAllocation>()
    private val mapCounts = HashMap<VmaAllocation, Int>()

    init {
        memoryStack {
            val memory = physicalDevice.getMemoryProperties()
            typeFlags = Array(memory.memoryTypeCount) { memory.memoryTypes(it).propertyFlags }
            typeHeap = IntArray(memory.memoryTypeCount) { memory.memoryTypes(it).heapIndex }
            heapSize = LongArray(memory.memoryHeapCount) { memory.memoryHeaps(it).size }
            val properties = physicalDevice.getProperties()
            integrated = properties.deviceType == VkPhysicalDeviceType.IntegratedGpu
            atomSize = maxOf(properties.limits.nonCoherentAtomSize, 1L)
            granularity = maxOf(properties.limits.bufferImageGranularity, 1L)
        }
        blocks = Array(typeFlags.size) { ArrayList() }
    }

    /** The property flags of the memory type of [allocation]. */
    fun getMemoryProperties(allocation: VmaAllocation): VkMemoryPropertyFlags = typeFlags[allocation.memoryTypeIndex]

    /** Returns the best memory type in [memoryTypeBits] for [info]. [usage] is the usage of the buffer or image, or -1 when it is not known. */
    fun findMemoryTypeIndex(memoryTypeBits: Int, info: VmaAllocationCreateInfo, usage: Int = -1): Int =
        candidates(memoryTypeBits, info, usage).firstOrNull() ?: throw VmaException("No memory type fits the allocation")

    /** Returns the best memory type for a buffer with [bufferInfo]. */
    context(stack: MemoryStack)
    fun findMemoryTypeIndexForBufferInfo(bufferInfo: VkBufferCreateInfo, info: VmaAllocationCreateInfo): Int {
        val buffer = device.createBuffer(bufferInfo)
        try {
            return findMemoryTypeIndex(device.getBufferMemoryRequirements(buffer).memoryTypeBits, info, bufferInfo.usage.value)
        } finally {
            device.destroy(buffer)
        }
    }

    /** Returns the best memory type for an image with [imageInfo]. */
    context(stack: MemoryStack)
    fun findMemoryTypeIndexForImageInfo(imageInfo: VkImageCreateInfo, info: VmaAllocationCreateInfo): Int {
        val image = device.createImage(imageInfo)
        try {
            return findMemoryTypeIndex(device.getImageMemoryRequirements(image).memoryTypeBits, info, imageInfo.usage.value)
        } finally {
            device.destroy(image)
        }
    }

    /** Allocates memory for the [size], [alignment], and [memoryTypeBits] of a resource. */
    fun allocateMemory(size: Long, alignment: Long, memoryTypeBits: Int, info: VmaAllocationCreateInfo, usage: Int = -1): VmaAllocation =
        allocate(size, alignment, memoryTypeBits, info, usage, VkBuffer.NULL, VkImage.NULL, dedicatedPreferred = false)

    /** Creates a buffer, allocates memory for it, and binds the memory. */
    context(stack: MemoryStack)
    fun createBuffer(bufferInfo: VkBufferCreateInfo, info: VmaAllocationCreateInfo): VmaBuffer {
        val buffer = device.createBuffer(bufferInfo)
        try {
            val dedicatedRequirements = VkMemoryDedicatedRequirements()
            val requirements = VkMemoryRequirements2().extend(dedicatedRequirements)
            device.getBufferMemoryRequirements2(VkBufferMemoryRequirementsInfo2 { this.buffer = buffer }, requirements)
            val memory = requirements.memoryRequirements
            val allocation = allocate(
                memory.size, memory.alignment, memory.memoryTypeBits, info, bufferInfo.usage.value, buffer, VkImage.NULL,
                dedicatedRequirements.prefersDedicatedAllocation || dedicatedRequirements.requiresDedicatedAllocation,
            )
            try {
                device.bindBufferMemory(buffer, allocation.memory, allocation.offset)
            } catch (error: Throwable) {
                freeMemory(allocation)
                throw error
            }
            return VmaBuffer(buffer, allocation)
        } catch (error: Throwable) {
            device.destroy(buffer)
            throw error
        }
    }

    /** Creates an image, allocates memory for it, and binds the memory. */
    context(stack: MemoryStack)
    fun createImage(imageInfo: VkImageCreateInfo, info: VmaAllocationCreateInfo): VmaImage {
        val image = device.createImage(imageInfo)
        try {
            val dedicatedRequirements = VkMemoryDedicatedRequirements()
            val requirements = VkMemoryRequirements2().extend(dedicatedRequirements)
            device.getImageMemoryRequirements2(VkImageMemoryRequirementsInfo2 { this.image = image }, requirements)
            val memory = requirements.memoryRequirements
            val allocation = allocate(
                memory.size, memory.alignment, memory.memoryTypeBits, info, imageInfo.usage.value, VkBuffer.NULL, image,
                dedicatedRequirements.prefersDedicatedAllocation || dedicatedRequirements.requiresDedicatedAllocation,
            )
            try {
                device.bindImageMemory(image, allocation.memory, allocation.offset)
            } catch (error: Throwable) {
                freeMemory(allocation)
                throw error
            }
            return VmaImage(image, allocation)
        } catch (error: Throwable) {
            device.destroy(image)
            throw error
        }
    }

    /** Creates a buffer and binds it to [allocation] at [offset], so it shares the memory of another resource. */
    context(stack: MemoryStack)
    fun createAliasingBuffer(allocation: VmaAllocation, bufferInfo: VkBufferCreateInfo, offset: Long = 0): VkBuffer {
        val buffer = device.createBuffer(bufferInfo)
        try {
            val requirements = device.getBufferMemoryRequirements(buffer)
            check(requirements.size + offset <= allocation.size) { "The aliasing buffer does not fit in the allocation" }
            check((1 shl allocation.memoryTypeIndex) and requirements.memoryTypeBits != 0) { "The aliasing buffer cannot use the memory type of the allocation" }
            device.bindBufferMemory(buffer, allocation.memory, allocation.offset + offset)
            return buffer
        } catch (error: Throwable) {
            device.destroy(buffer)
            throw error
        }
    }

    /** Creates an image and binds it to [allocation] at [offset], so it shares the memory of another resource. */
    context(stack: MemoryStack)
    fun createAliasingImage(allocation: VmaAllocation, imageInfo: VkImageCreateInfo, offset: Long = 0): VkImage {
        val image = device.createImage(imageInfo)
        try {
            val requirements = device.getImageMemoryRequirements(image)
            check(requirements.size + offset <= allocation.size) { "The aliasing image does not fit in the allocation" }
            check((1 shl allocation.memoryTypeIndex) and requirements.memoryTypeBits != 0) { "The aliasing image cannot use the memory type of the allocation" }
            device.bindImageMemory(image, allocation.memory, allocation.offset + offset)
            return image
        } catch (error: Throwable) {
            device.destroy(image)
            throw error
        }
    }

    /** Destroys [buffer] and releases [allocation]. */
    fun destroyBuffer(buffer: VkBuffer, allocation: VmaAllocation) {
        device.destroy(buffer)
        freeMemory(allocation)
    }

    /** Destroys [image] and releases [allocation]. */
    fun destroyImage(image: VkImage, allocation: VmaAllocation) {
        device.destroy(image)
        freeMemory(allocation)
    }

    /** Releases [allocation]. */
    fun freeMemory(allocation: VmaAllocation) = locked {
        val block = allocation.block
        if (block == null) {
            dedicated.remove(allocation)
            mapCounts.remove(allocation)
            device.freeMemory(allocation.memory)
            return@locked
        }
        repeat(mapCounts.remove(allocation) ?: 0) { unmapBlock(block) }
        block.tlsf.free(allocation.node!!)
        val list = blocks[block.type]
        if (block.tlsf.isEmpty && list.count { it.tlsf.isEmpty } > 1) {
            list.remove(block)
            if (block.mapped != 0L) device.unmapMemory(block.memory)
            device.freeMemory(block.memory)
        }
    }

    /** Maps [allocation] and returns its host address. Call [unmapMemory] once for each call. */
    fun mapMemory(allocation: VmaAllocation): Long = locked {
        val block = allocation.block
        mapCounts[allocation] = (mapCounts[allocation] ?: 0) + 1
        if (block == null) {
            if (allocation.dedicatedMapping != 0L) allocation.dedicatedMapping else memoryStack { device.mapMemory(allocation.memory, 0, Vk.WHOLE_SIZE) }
        } else mapBlock(block) + allocation.offset
    }

    /** Releases one mapping of [allocation]. */
    fun unmapMemory(allocation: VmaAllocation) = locked {
        val count = mapCounts[allocation] ?: return@locked
        if (count == 1) mapCounts.remove(allocation) else mapCounts[allocation] = count - 1
        val block = allocation.block
        if (block == null) {
            if (count == 1 && allocation.dedicatedMapping == 0L) device.unmapMemory(allocation.memory)
        } else unmapBlock(block)
    }

    /** Makes host writes to [size] bytes at [offset] of [allocation] visible to the device. Does nothing for coherent memory. */
    fun flushAllocation(allocation: VmaAllocation, offset: Long = 0, size: Long = Vk.WHOLE_SIZE) =
        range(allocation, offset, size) { device.flushMappedMemoryRanges(listOf(it)) }

    /** Makes device writes to [size] bytes at [offset] of [allocation] visible to the host. Does nothing for coherent memory. */
    fun invalidateAllocation(allocation: VmaAllocation, offset: Long = 0, size: Long = Vk.WHOLE_SIZE) =
        range(allocation, offset, size) { device.invalidateMappedMemoryRanges(listOf(it)) }

    override fun close() = locked {
        for (allocation in dedicated) device.freeMemory(allocation.memory)
        dedicated.clear()
        mapCounts.clear()
        for (list in blocks) {
            for (block in list) {
                if (block.mapped != 0L) device.unmapMemory(block.memory)
                device.freeMemory(block.memory)
            }
            list.clear()
        }
    }

    private inline fun range(allocation: VmaAllocation, offset: Long, size: Long, apply: context(MemoryStack) (VkMappedMemoryRange) -> Unit) {
        if (VkMemoryPropertyFlags.HostCoherent in typeFlags[allocation.memoryTypeIndex]) return
        val length = if (size == Vk.WHOLE_SIZE) allocation.size - offset else size
        val memorySize = allocation.block?.size ?: allocation.size
        val start = (allocation.offset + offset) / atomSize * atomSize
        val end = minOf((allocation.offset + offset + length + atomSize - 1) / atomSize * atomSize, memorySize)
        memoryStack {
            apply(VkMappedMemoryRange {
                memory = allocation.memory
                this.offset = start
                this.size = if (end == memorySize && allocation.block == null) Vk.WHOLE_SIZE else end - start
            })
        }
    }

    private fun mapBlock(block: Block): Long {
        if (block.mapCount++ == 0) block.mapped = memoryStack { device.mapMemory(block.memory, 0, Vk.WHOLE_SIZE) }
        return block.mapped
    }

    private fun unmapBlock(block: Block) {
        if (--block.mapCount == 0) {
            device.unmapMemory(block.memory)
            block.mapped = 0L
        }
    }

    private fun candidates(memoryTypeBits: Int, info: VmaAllocationCreateInfo, usage: Int): List<Int> {
        var required = info.requiredFlags.value
        var preferred = info.preferredFlags.value
        var notPreferred = 0
        val deviceLocal = VkMemoryPropertyFlags.DeviceLocal.value
        val hostVisible = VkMemoryPropertyFlags.HostVisible.value
        val hostCached = VkMemoryPropertyFlags.HostCached.value
        when (info.usage) {
            VmaMemoryUsage.GpuLazilyAllocated -> required = required or VkMemoryPropertyFlags.LazilyAllocated.value
            VmaMemoryUsage.Auto, VmaMemoryUsage.AutoPreferDevice, VmaMemoryUsage.AutoPreferHost -> {
                require(usage != -1) { "VmaMemoryUsage.Auto needs the usage of the buffer or image" }
                val deviceAccess = usage and 0x3.inv() != 0
                val sequential = VmaAllocationCreateFlags.HostAccessSequentialWrite in info.flags
                val random = VmaAllocationCreateFlags.HostAccessRandom in info.flags
                val transferInstead = VmaAllocationCreateFlags.HostAccessAllowTransferInstead in info.flags
                val preferDevice = info.usage == VmaMemoryUsage.AutoPreferDevice
                val preferHost = info.usage == VmaMemoryUsage.AutoPreferHost
                when {
                    random -> if (!integrated && deviceAccess && transferInstead && !preferHost) preferred = preferred or deviceLocal
                    else required = required or hostVisible or hostCached
                    sequential -> {
                        notPreferred = notPreferred or hostCached
                        if (!integrated && deviceAccess && transferInstead && !preferHost) preferred = preferred or deviceLocal
                        else {
                            required = required or hostVisible
                            if (deviceAccess) {
                                if (preferHost) notPreferred = notPreferred or deviceLocal else preferred = preferred or deviceLocal
                            } else {
                                if (preferDevice) preferred = preferred or deviceLocal else notPreferred = notPreferred or deviceLocal
                            }
                        }
                    }
                    preferHost -> notPreferred = notPreferred or deviceLocal
                    else -> preferred = preferred or deviceLocal
                }
            }
        }
        val amd = VkMemoryPropertyFlags.DeviceCoherentAMD.value or VkMemoryPropertyFlags.DeviceUncachedAMD.value
        if ((required or preferred) and amd == 0) notPreferred = notPreferred or amd
        val bits = if (info.memoryTypeBits != 0) memoryTypeBits and info.memoryTypeBits else memoryTypeBits
        return typeFlags.indices
            .filter { bits and (1 shl it) != 0 && required and typeFlags[it].value.inv() == 0 }
            .sortedBy { (preferred and typeFlags[it].value.inv()).countOneBits() + (typeFlags[it].value and notPreferred).countOneBits() }
    }

    private fun allocate(
        size: Long,
        alignment: Long,
        memoryTypeBits: Int,
        info: VmaAllocationCreateInfo,
        usage: Int,
        buffer: VkBuffer,
        image: VkImage,
        dedicatedPreferred: Boolean,
    ): VmaAllocation = locked {
        val types = candidates(memoryTypeBits, info, usage)
        if (types.isEmpty()) throw VmaException("No memory type fits the allocation")
        for (type in types) {
            val blockSize = blockSize(type)
            val dedicatedAllocation = VmaAllocationCreateFlags.DedicatedMemory in info.flags || dedicatedPreferred || size > blockSize / 2
            val allocation = if (dedicatedAllocation) allocateDedicated(size, type, info, buffer, image)
            else allocateInBlock(size, alignment, type, blockSize, info) ?: allocateDedicated(size, type, info, buffer, image)
            if (allocation != null) return@locked allocation
        }
        throw VmaException("Out of device memory for an allocation of $size bytes")
    }

    private fun blockSize(type: Int): Long {
        val heap = heapSize[typeHeap[type]]
        return if (heap <= 1L shl 30) heap / 8 else preferredLargeHeapBlockSize
    }

    private fun allocateInBlock(size: Long, alignment: Long, type: Int, blockSize: Long, info: VmaAllocationCreateInfo): VmaAllocation? {
        val align = if (granularity > 1) maxOf(alignment, granularity) else alignment
        val list = blocks[type]
        for (block in list) block.tlsf.allocate(size, align)?.let { return suballocation(block, it, info) }
        if (VmaAllocationCreateFlags.NeverAllocate in info.flags) return null
        var tried = blockSize
        repeat(4) {
            if (tried < size) return null
            val memory = allocateDeviceMemory(tried, type, VkBuffer.NULL, VkImage.NULL)
            if (memory != null) {
                val block = Block(memory, tried, type)
                list += block
                return suballocation(block, block.tlsf.allocate(size, align)!!, info)
            }
            tried /= 2
        }
        return null
    }

    private fun suballocation(block: Block, node: Tlsf.Node, info: VmaAllocationCreateInfo): VmaAllocation {
        val allocation = VmaAllocation(block.memory, node.offset, node.size, block.type, block, node, 0L)
        if (VmaAllocationCreateFlags.Mapped in info.flags) {
            mapBlock(block)
            mapCounts[allocation] = 1
        }
        return allocation
    }

    private fun allocateDedicated(size: Long, type: Int, info: VmaAllocationCreateInfo, buffer: VkBuffer, image: VkImage): VmaAllocation? {
        if (VmaAllocationCreateFlags.NeverAllocate in info.flags) return null
        val memory = allocateDeviceMemory(size, type, buffer, image) ?: return null
        val mapped = if (VmaAllocationCreateFlags.Mapped in info.flags) memoryStack { device.mapMemory(memory, 0, Vk.WHOLE_SIZE) } else 0L
        return VmaAllocation(memory, 0, size, type, null, null, mapped).also { dedicated += it }
    }

    private fun allocateDeviceMemory(size: Long, type: Int, buffer: VkBuffer, image: VkImage): VkDeviceMemory? = memoryStack {
        val allocateInfo = VkMemoryAllocateInfo {
            allocationSize = size
            memoryTypeIndex = type
        }
        if (VmaAllocatorCreateFlags.BufferDeviceAddress in flags) allocateInfo.extend(VkMemoryAllocateFlagsInfo { this.flags = VkMemoryAllocateFlags.DeviceAddress })
        if (!buffer.isNull || !image.isNull) allocateInfo.extend(VkMemoryDedicatedAllocateInfo {
            this.buffer = buffer
            this.image = image
        })
        try {
            device.allocateMemory(allocateInfo)
        } catch (_: VulkanException) {
            null
        }
    }

    private inline fun <T> locked(block: () -> T): T {
        while (!lock.compareAndSet(false, true)) Unit
        try {
            return block()
        } finally {
            lock.store(false)
        }
    }
}
