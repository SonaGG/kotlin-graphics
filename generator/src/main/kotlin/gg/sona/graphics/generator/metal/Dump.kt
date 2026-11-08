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
package gg.sona.graphics.generator.metal

import java.io.File

class DumpParam(val name: String, val type: String)

class DumpMethod(val name: String, val selector: String, val params: List<DumpParam>, val returns: String)

class DumpClass(val source: String, val protocol: Boolean, val supers: List<String>) {
    val methods = ArrayList<DumpMethod>()
    val classMethods = ArrayList<DumpMethod>()
    var constructible = false
}

class DumpField(val name: String, val type: String, val readOnly: Boolean, var offset: Long = 0)

class DumpStruct(val source: String) {
    val fields = ArrayList<DumpField>()
    var size = 0L
    var align = 1L
}

class DumpConstant(val name: String, val base: String, val typedef: String?, val value: String)

class DumpFunction(val name: String, val params: List<DumpParam>, val returns: String)

class Dump(files: List<File>) {
    val classes = LinkedHashMap<String, DumpClass>()
    val structs = LinkedHashMap<String, DumpStruct>()
    val constants = ArrayList<DumpConstant>()
    val functions = ArrayList<DumpFunction>()
    val enumClasses = HashSet<String>()

    private val header = Regex("""^    (?:@\S+ )*public (abstract interface|open class|final class) (\S+) : (.*) \{$""")
    private val companion = Regex("""^    public final companion object (\S+)\.Companion : .*\{$""")
    private val method = Regex("""^      public .*?fun (\w+)\((.*)\): (.+)$""")
    private val selector = Regex("""ObjCMethod\(selector = "([^"]+)"""")
    private val field = Regex("""^      public final (va[lr]) (\w+): (.+)$""")
    private val memberAt = Regex("""CStruct\.MemberAt\(offset = (\d+)L\)""")
    private val varType = Regex("""CStruct\.VarType\(size = (\d+)L, align = (\d+)\)""")
    private val enumHeader = Regex("""^    public final enum class (\S+) : kotlin/Enum<""")
    private val enumBase = Regex("""^      private constructor\(value: (\S+)""")
    private val enumValue = Regex("""ConstantValue\.\w+\(value = (-?\d+)\w*\)""")
    private val enumEntry = Regex("""^      (\w+)[,;]$""")
    private val constant = Regex("""^      public final const val (\w+): (\S+)(?: /\* = (\S+?)\^ \*/)? /\* = (.+?) \*/$""")

    private val metaclasses = HashMap<String, DumpClass>()

    init {
        for (file in files) parse(file.readLines())
        for ((source, meta) in metaclasses) classes[source.removeSuffix("Meta")]?.classMethods?.addAll(meta.methods.filter { it.name !in setOf("new", "alloc", "allocWithZone") })
        structs["platform/CoreGraphics/CGSize"] = DumpStruct("platform/CoreGraphics/CGSize").apply {
            size = 16
            align = 8
            fields += DumpField("width", "kotlin/Double", false, 0)
            fields += DumpField("height", "kotlin/Double", false, 8)
        }
        structs["platform/CoreGraphics/CGRect"] = DumpStruct("platform/CoreGraphics/CGRect").apply {
            size = 32
            align = 8
            fields += DumpField("x", "kotlin/Double", false, 0)
            fields += DumpField("y", "kotlin/Double", false, 8)
            fields += DumpField("width", "kotlin/Double", false, 16)
            fields += DumpField("height", "kotlin/Double", false, 24)
        }
        structs["platform/Foundation/_NSRange"] = DumpStruct("platform/Foundation/_NSRange").apply {
            size = 16
            align = 8
            fields += DumpField("location", "kotlin/ULong /* = platform/darwin/NSUInteger^ */", false, 0)
            fields += DumpField("length", "kotlin/ULong /* = platform/darwin/NSUInteger^ */", false, 8)
        }
    }

    private fun parse(lines: List<String>) {
        var cls: DumpClass? = null
        var struct: DumpStruct? = null
        var statics: DumpClass? = null
        var pendingSelector: String? = null
        var pendingInit = false
        var pendingVarType: Pair<Long, Long>? = null
        var lastField: DumpField? = null
        var enumClass: String? = null
        var enumType = "kotlin/Int"
        var pendingValue: String? = null
        for (line in lines) {
            enumHeader.find(line)?.let {
                enumClass = it.groupValues[1]
                enumClasses += it.groupValues[1]
                continue
            }
            enumClass?.let { source ->
                if (line == "    }") {
                    enumClass = null
                } else {
                    enumBase.find(line)?.let { enumType = it.groupValues[1] }
                    enumValue.find(line)?.let { pendingValue = it.groupValues[1] }
                    enumEntry.find(line)?.let { entry ->
                        pendingValue?.let { constants += DumpConstant(entry.groupValues[1], enumType, source, it) }
                        pendingValue = null
                    }
                }
                continue
            }
            if (line == "    }") {
                cls = null
                struct = null
                statics = null
                pendingSelector = null
                continue
            }
            header.find(line)?.let { match ->
                val (kind, source, supers) = match.destructured
                if (supers.startsWith("kotlinx/cinterop/CStructVar")) {
                    struct = DumpStruct(source).also { structs[source] = it }
                } else if (source.endsWith("Meta") && kind != "abstract interface") {
                    cls = DumpClass(source, false, emptyList()).also { metaclasses[source] = it }
                } else if (!source.endsWith("Meta") && '.' !in source.substringAfterLast('/') && !supers.startsWith("kotlinx/cinterop/CEnumVar")) {
                    cls = DumpClass(source, kind == "abstract interface", splitTop(supers).map { it.trim().substringBefore(' ') })
                        .also { classes[source] = it }
                }
                return@let
            }
            companion.find(line)?.let { match ->
                val source = match.groupValues[1]
                pendingVarType?.let { (size, align) -> structs[source]?.let { it.size = size; it.align = align } }
                pendingVarType = null
                statics = classes[source]
                return@let
            }
            varType.find(line)?.let { pendingVarType = it.groupValues[1].toLong() to it.groupValues[2].toLong() }
            selector.find(line)?.let { pendingSelector = it.groupValues[1] }
            if ("ObjCConstructor(" in line && "initSelector = \"init\"" in line) pendingInit = true
            val current = struct
            if (current != null) {
                field.find(line)?.let { lastField = DumpField(it.groupValues[2], it.groupValues[3], it.groupValues[1] == "val").also(current.fields::add) }
                memberAt.find(line)?.let { lastField?.offset = it.groupValues[1].toLong() }
                continue
            }
            if (line.startsWith("      public /* secondary */ constructor()") && pendingInit) {
                cls?.constructible = true
                pendingInit = false
                continue
            }
            val target = statics ?: cls
            method.find(line)?.let { match ->
                val (name, params, returns) = match.destructured
                val sel = pendingSelector
                pendingSelector = null
                if (target != null && sel != null) {
                    val parsed = DumpMethod(name, sel, params(params), returns)
                    if (statics != null) target.classMethods += parsed else target.methods += parsed
                } else if (target == null && "external fun" in line) {
                    functions += DumpFunction(name, params(params), returns)
                }
                return@let
            }
            if (cls == null && statics == null) constant.find(line)?.let { match ->
                val (name, base, typedef, value) = match.destructured
                constants += DumpConstant(name, base, typedef.ifEmpty { null }, value)
            }
        }
    }

    private fun params(text: String): List<DumpParam> = splitTop(text).filter { it.isNotBlank() }.map { part ->
        val trimmed = part.trim()
        DumpParam(trimmed.substringBefore(':').trim().split(' ').last(), trimmed.substringAfter(": "))
    }

    companion object {
        fun splitTop(text: String): List<String> {
            val parts = ArrayList<String>()
            var depth = 0
            var comment = false
            var start = 0
            var i = 0
            while (i < text.length) {
                when {
                    text.startsWith("/*", i) -> { comment = true; i++ }
                    text.startsWith("*/", i) -> { comment = false; i++ }
                    comment -> {}
                    text[i] == '<' || text[i] == '(' -> depth++
                    text[i] == '>' || text[i] == ')' -> depth--
                    text[i] == ',' && depth == 0 -> {
                        parts += text.substring(start, i)
                        start = i + 1
                    }
                }
                i++
            }
            parts += text.substring(start)
            return parts
        }

        fun typedefOf(type: String): String? = Regex("""/\* = (\S+?)\^\?? \*/""").find(type)?.groupValues?.get(1)

        fun baseOf(type: String): String = type.replace(Regex("""/\*.*?\*/"""), "").trim()
    }
}
