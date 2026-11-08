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
package gg.sona.graphics.generator

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.BYTE
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.DOUBLE
import com.squareup.kotlinpoet.FLOAT
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.SHORT
import com.squareup.kotlinpoet.TypeName

const val PACKAGE = "gg.sona.graphics.vulkan"

class Scalar(val type: TypeName, val access: String, val kind: Char, val size: Long)

val POINTER = Scalar(LONG, "Long", 'J', 8)
val INT8 = Scalar(BYTE, "Byte", 'B', 1)
val INT16 = Scalar(SHORT, "Short", 'S', 2)
val INT32 = Scalar(INT, "Int", 'I', 4)
val INT64 = Scalar(LONG, "Long", 'J', 8)
val FLOAT32 = Scalar(FLOAT, "Float", 'F', 4)
val FLOAT64 = Scalar(DOUBLE, "Double", 'D', 8)

sealed class Ty(val size: Long, val align: Long)
class Plain(val scalar: Scalar) : Ty(scalar.size, scalar.size)
class Bool : Ty(4, 4)
class EnumTy(val name: String, val scalar: Scalar) : Ty(scalar.size, scalar.size)
class FlagsTy(val name: String, val scalar: Scalar) : Ty(scalar.size, scalar.size)
class Handle(val name: String, val dispatchable: Boolean) : Ty(8, 8)
class Layout(val name: String, size: Long, align: Long) : Ty(size, align)

val POINTER_TY = Plain(POINTER)

val DISPATCHABLE = listOf("VkInstance", "VkPhysicalDevice", "VkDevice", "VkQueue", "VkCommandBuffer")
val INSTANCE_LEVEL = setOf("VkInstance", "VkPhysicalDevice")

fun vk(name: String) = ClassName(PACKAGE, name)

fun Ty.scalar(): Scalar = when (this) {
    is Plain -> scalar
    is Bool -> INT32
    is EnumTy -> scalar
    is FlagsTy -> scalar
    is Handle -> POINTER
    is Layout -> error("A struct has no scalar form")
}

fun Ty.type(): TypeName = when (this) {
    is Plain -> scalar.type
    is Bool -> BOOLEAN
    is EnumTy -> vk(name)
    is FlagsTy -> vk(name)
    is Handle -> if (dispatchable) LONG else vk(name)
    is Layout -> vk(name)
}

private val EXTERNAL = mapOf(
    "char" to INT8, "int8_t" to INT8, "uint8_t" to INT8,
    "int16_t" to INT16, "uint16_t" to INT16,
    "int" to INT32, "int32_t" to INT32, "uint32_t" to INT32, "DWORD" to INT32, "xcb_window_t" to INT32, "xcb_visualid_t" to INT32,
    "int64_t" to INT64, "uint64_t" to INT64, "size_t" to INT64, "Window" to INT64, "VisualID" to INT64, "RROutput" to INT64,
    "float" to FLOAT32, "double" to FLOAT64,
    "HINSTANCE" to POINTER, "HWND" to POINTER, "HMONITOR" to POINTER, "HANDLE" to POINTER, "LPCWSTR" to POINTER,
)

class Field(val decl: Declaration, val ty: Ty?, val count: Long, val offset: Long, val shift: Int, val bits: Int?) {
    val name get() = decl.name
    val pointer get() = decl.pointers > 0
}

class StructLayout(val name: String, val fields: List<Field>, val size: Long, val align: Long, val sType: String?, val extends: List<String>)

class Model(val registry: Registry) {
    private val layouts = HashMap<String, StructLayout?>()
    private val pending = HashSet<String>()
    private val flagsOfBits = HashMap<String, String>()

    init {
        for (type in registry.types.values) if (type.category == "bitmask" && type.alias == null) {
            val bits = type.element.getAttribute("requires").ifEmpty { type.element.getAttribute("bitvalues") }
            if (bits.isNotEmpty()) flagsOfBits[bits] = type.name
        }
    }

    fun isFlagBits(name: String) = name in flagsOfBits

    fun canonical(name: String): String {
        var current = name
        while (true) current = registry.types[current]?.alias ?: return current
    }

    fun resolve(name: String): Ty? {
        EXTERNAL[name]?.let { return Plain(it) }
        if (name == "VkBool32") return Bool()
        val type = registry.types[canonical(name)] ?: return null
        val element = type.element
        return when (type.category) {
            "basetype" -> element.child("type")?.let { if ('*' in element.textContent) POINTER_TY else resolve(it.textContent) }
                ?: if ('*' in element.textContent) POINTER_TY else null
            "bitmask" -> FlagsTy(type.name, (resolve(element.child("type")!!.textContent) as Plain).scalar)
            "enum" -> {
                val scalar = if (registry.enumBitwidth[type.name] == 64) INT64 else INT32
                flagsOfBits[type.name]?.let { FlagsTy(it, scalar) } ?: EnumTy(type.name, scalar)
            }
            "handle" -> Handle(type.name, element.child("type")?.textContent == "VK_DEFINE_HANDLE")
            "funcpointer" -> POINTER_TY
            "struct", "union" -> layout(type.name)?.let { Layout(it.name, it.size, it.align) }
            else -> null
        }
    }

    private fun dimension(text: String): Long =
        text.toLongOrNull() ?: registry.apiConstants[text]?.literal?.removeSuffix("L")?.toLongOrNull() ?: error("Unknown array size $text")

    fun layout(name: String): StructLayout? {
        if (name in layouts) return layouts[name]
        check(pending.add(name)) { "Recursive struct $name" }
        val type = registry.types.getValue(name)
        val union = type.category == "union"
        val fields = ArrayList<Field>()
        var offset = 0L
        var align = 1L
        var size = 0L
        var bitUnit = -1L
        var bitUsed = 0
        var sType: String? = null
        var valid = true
        for (member in type.element.children("member")) {
            if (member.getAttribute("api").let { it.isNotEmpty() && "vulkan" !in it.split(',') }) continue
            val decl = Registry.declaration(member)
            val target = registry.types[canonical(decl.type)]
                ?.takeIf { decl.pointers > 0 && (it.category == "struct" || it.category == "union") }
                ?.let { Layout(it.name, 0, 1) }
                ?: resolve(decl.type)
            val memory: Ty = if (decl.pointers > 0) POINTER_TY else target ?: run { valid = false; null } ?: break
            val count = decl.dims.fold(1L) { total, dim -> total * dimension(dim) }
            if (decl.name == "sType") sType = decl.values
            val bits = decl.bits
            val fieldOffset: Long
            var shift = 0
            if (bits != null && bitUnit >= 0 && bitUsed + bits <= memory.size * 8) {
                fieldOffset = bitUnit
                shift = bitUsed
                bitUsed += bits
            } else {
                fieldOffset = if (union) 0 else (offset + memory.align - 1) / memory.align * memory.align
                offset = fieldOffset + memory.size * count
                if (bits != null) {
                    bitUnit = fieldOffset
                    bitUsed = bits
                } else bitUnit = -1
            }
            align = maxOf(align, memory.align)
            size = maxOf(size, fieldOffset + memory.size * count)
            fields += Field(decl, target, count, fieldOffset, shift, bits)
        }
        pending.remove(name)
        val extends = type.element.getAttribute("structextends").split(',').filter { it.isNotEmpty() }
        val result = if (valid) StructLayout(name, fields, (size + align - 1) / align * align, align, sType, extends) else null
        layouts[name] = result
        return result
    }

    private val camel = Regex("[A-Z]+(?![a-z])|[A-Z][a-z]*|\\d+[A-Za-z]?(?![a-z])|[a-z]+")

    private fun words(typeName: String) = camel.findAll(typeName.removePrefix("Vk")).map { it.value }.toList()

    fun word(text: String): String = when {
        text in registry.tags -> text
        Regex("[A-Z]+\\d+").matches(text) -> text[0] + text.substring(1).lowercase()
        text.any { it.isDigit() } -> text
        else -> text[0] + text.substring(1).lowercase()
    }

    fun valueNames(type: String, values: Collection<String>, flags: Boolean): Map<String, String> {
        val typeWords = words(type).toMutableList()
        val vendor = typeWords.lastOrNull()?.takeIf { it in registry.tags }
        if (vendor != null) typeWords.removeAt(typeWords.lastIndex)
        if (flags) {
            val index = typeWords.indexOf("Flag")
            if (index >= 0) {
                val suffix = typeWords.drop(index + 2)
                while (typeWords.size > index) typeWords.removeAt(typeWords.lastIndex)
                typeWords += suffix
            }
            if (typeWords.lastOrNull() == "Flags") typeWords.removeAt(typeWords.lastIndex)
        }
        val expected = "VK_" + typeWords.joinToString("_") { it.uppercase() } + "_"
        val split = values.map { it.split('_') }
        val common = if (values.all { it.startsWith(expected) }) expected.split('_').size - 1 else {
            var length = 0
            val first = split.firstOrNull() ?: return emptyMap()
            while (length < first.size - 1 && split.all { it.size > length + 1 && it[length] == first[length] }) length++
            length
        }
        val last = typeWords.lastOrNull { it.first().isLetter() } ?: "Value"
        val result = LinkedHashMap<String, String>()
        for ((name, parts) in values.zip(split)) {
            val rest = parts.drop(common).toMutableList()
            if (vendor != null && rest.lastOrNull() == vendor) rest.removeAt(rest.lastIndex)
            if (flags) {
                val bit = rest.lastIndexOf("BIT")
                if (bit >= 0 && rest.drop(bit + 1).all { it in registry.tags }) rest.removeAt(bit)
            }
            if (rest.isEmpty()) continue
            var kotlin = rest.joinToString("") { word(it) }
            if (kotlin.first().isDigit()) kotlin = last + kotlin
            if (kotlin !in result.values) result[name] = kotlin
        }
        return result
    }

    fun numericValues(type: String) = registry.enumValues[type].orEmpty().values.filter {
        it.literal.first().let { c -> c.isDigit() || c == '-' } || it.literal.startsWith("Int.") || it.literal.startsWith("Long.")
    }

    val structureTypes by lazy { valueNames("VkStructureType", numericValues("VkStructureType").map { it.name }, flags = false) }

    fun memberName(decl: Declaration): String {
        var name = decl.name
        if (decl.pointers > 0) {
            val stripped = name.dropWhile { it == 'p' }
            if (stripped.isNotEmpty() && stripped[0].isUpperCase() && name.length - stripped.length <= 2) name = stripped.replaceFirstChar { it.lowercase() }
        }
        if (name.startsWith("pfn") && name.length > 3 && name[3].isUpperCase()) name = name.drop(3).replaceFirstChar { it.lowercase() }
        return if (name == "address") "deviceAddress" else name
    }

    fun arrayType(ty: Ty): TypeName? = when {
        ty is Plain && ty.scalar === FLOAT32 -> ClassName("kotlin", "FloatArray")
        ty is Plain && ty.scalar === INT32 -> ClassName("kotlin", "IntArray")
        ty is Plain && (ty.scalar === INT64 || ty.scalar === POINTER) -> ClassName("kotlin", "LongArray")
        ty is Plain && ty.scalar === INT8 -> ClassName("kotlin", "ByteArray")
        else -> null
    }

    fun receiverOf(command: CommandDef): String? {
        val first = command.params.firstOrNull() ?: return null
        return canonical(first.type).takeIf { first.pointers == 0 && it in DISPATCHABLE }
    }

    fun methodName(command: String, receiver: String?): String {
        var base = command.removePrefix("vk")
        if (receiver == "VkCommandBuffer" && base.startsWith("Cmd")) base = base.removePrefix("Cmd")
        val noun = receiver?.removePrefix("Vk")
        if (noun != null) {
            val verbEnd = (1 until base.length).firstOrNull { base[it].isUpperCase() } ?: base.length
            val verb = base.substring(0, verbEnd)
            val rest = base.substring(verbEnd)
            base = when {
                base.startsWith(noun) && base.length > noun.length && base[noun.length].isUpperCase() -> base.removePrefix(noun)
                rest == noun -> verb
                rest.startsWith(noun) && rest.length > noun.length && rest[noun.length].isUpperCase() -> verb + rest.removePrefix(noun)
                else -> base
            }
        }
        return base.replaceFirstChar { it.lowercase() }
    }
}
