package gg.sona.graphics.vulkan

import gg.sona.graphics.runtime.memoryStack
import gg.sona.graphics.runtime.stack
import kotlin.jvm.JvmInline

/** A packed Vulkan version number. */
@JvmInline
value class VkApiVersion(val value: Int) {
    val variant get() = value ushr 29
    val major get() = (value ushr 22) and 0x7F
    val minor get() = (value ushr 12) and 0x3FF
    val patch get() = value and 0xFFF

    override fun toString() = "$major.$minor.$patch"

    companion object {
        fun of(major: Int, minor: Int, patch: Int = 0, variant: Int = 0) =
            VkApiVersion((variant shl 29) or (major shl 22) or (minor shl 12) or patch)

        val V1_0 = of(1, 0)
        val V1_1 = of(1, 1)
        val V1_2 = of(1, 2)
        val V1_3 = of(1, 3)
        val V1_4 = of(1, 4)
    }
}

/** Thrown when a Vulkan command gives an error result. */
class VulkanException(val result: VkResult, val command: String) : RuntimeException("$command failed: $result")

/** Returns [result]. Throws a [VulkanException] when [result] is an error code. */
internal fun checkResult(result: Int, command: String): Int {
    if (result < 0) throw VulkanException(VkResult(result), command)
    return result
}

/** Returns the address of a loaded command. Throws when the instance or device does not give the command. */
internal fun function(table: LongArray, index: Int, command: String): Long {
    val address = table[index]
    if (address == 0L) throw UnsupportedOperationException("$command is not loaded. Enable its version or extension.")
    return address
}

/** Fills [table] with the address of each command in [names]. [lookup] takes the address of a UTF-8 name. */
internal inline fun loadTable(table: LongArray, names: Array<String>, lookup: (Long) -> Long) = memoryStack {
    for (i in names.indices) table[i] = lookup(stack.utf8(names[i]))
}
