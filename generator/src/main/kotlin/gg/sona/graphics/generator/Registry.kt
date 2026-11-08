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

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class Declaration(
    val name: String,
    val type: String,
    val pointers: Int,
    val dims: List<String>,
    val bits: Int?,
    val values: String?,
    val len: String?,
    val optional: Boolean,
    val isConst: Boolean,
)

class TypeDef(val name: String, val category: String, val element: Element) {
    val alias: String? = element.getAttribute("alias").ifEmpty { null }
}

class CommandDef(
    val name: String,
    val returnType: String,
    val returnPointers: Int,
    val params: List<Declaration>,
    val successCodes: List<String>,
    val errorCodes: List<String>,
)

class Constant(val name: String, val kotlinType: String, val literal: String)

class Registry(file: File, private val platforms: Set<String>) {
    private val root: Element = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement

    val types = LinkedHashMap<String, TypeDef>()
    val commands = LinkedHashMap<String, CommandDef>()
    val commandAliases = LinkedHashMap<String, String>()

    val enumValues = LinkedHashMap<String, LinkedHashMap<String, Constant>>()
    val enumBitwidth = HashMap<String, Int>()
    val apiConstants = LinkedHashMap<String, Constant>()
    val extensionConstants = LinkedHashMap<String, Constant>()

    val tags: Set<String> = root.child("tags")!!.children("tag").map { it.getAttribute("name") }.toSet()

    val requiredTypes = LinkedHashSet<String>()
    val requiredCommands = LinkedHashSet<String>()

    init {
        readTypes()
        readEnums()
        readCommands()
        for (feature in root.children("feature")) if (feature.supports("api")) readRequires(feature, null)
        for (extension in root.child("extensions")!!.children("extension")) {
            if (!extension.supports("supported")) continue
            val platform = extension.getAttribute("platform")
            if (platform.isNotEmpty() && platform !in platforms) continue
            readRequires(extension, extension.getAttribute("number").toInt())
        }
    }

    private fun Element.supports(attribute: String) = getAttribute(attribute).let { it.isEmpty() || "vulkan" in it.split(',') }

    private fun readTypes() {
        for (element in root.child("types")!!.children("type")) {
            if (!element.supports("api")) continue
            val category = element.getAttribute("category")
            val name = element.getAttribute("name").ifEmpty { element.child("name")?.textContent ?: element.descendant("name")?.textContent } ?: continue
            types[name] = TypeDef(name, category, element)
        }
    }

    private fun readEnums() {
        for (block in root.children("enums")) {
            val name = block.getAttribute("name")
            val values = LinkedHashMap<String, Constant>()
            val bitwidth = block.getAttribute("bitwidth").ifEmpty { "32" }.toInt()
            for (entry in block.children("enum")) {
                if (!entry.supports("api")) continue
                val constant = if (name == "API Constants") apiConstant(entry) else enumConstant(entry, bitwidth, null) ?: continue
                values[constant.name] = constant
            }
            if (name == "API Constants") apiConstants.putAll(values) else {
                enumValues[name] = values
                enumBitwidth[name] = bitwidth
            }
        }
    }

    private fun apiConstant(entry: Element): Constant {
        val name = entry.getAttribute("name")
        entry.getAttribute("alias").ifEmpty { null }?.let { return Constant(name, apiConstants.getValue(it).kotlinType, it) }
        val value = entry.getAttribute("value")
        return when (entry.getAttribute("type")) {
            "float" -> Constant(name, "Float", value.removeSuffix("F").removeSuffix("f") + "f")
            "uint64_t" -> Constant(name, "Long", longLiteral(cValue(value)))
            else -> Constant(name, "Int", intLiteral(cValue(value).toInt()))
        }
    }

    private fun cValue(text: String): Long {
        val value = text.removePrefix("(").removeSuffix(")").trimEnd('U', 'L', 'u', 'l')
        if (value.startsWith("~")) return cValue(value.drop(1)).inv()
        return if (value.startsWith("0x")) value.drop(2).toLong(16) else value.toLong()
    }

    private fun enumConstant(entry: Element, bitwidth: Int, extension: Int?): Constant? {
        val name = entry.getAttribute("name")
        val type = if (bitwidth == 64) "Long" else "Int"
        entry.getAttribute("alias").ifEmpty { null }?.let { return Constant(name, type, it) }
        val value: Long = when {
            entry.hasAttribute("bitpos") -> 1L shl entry.getAttribute("bitpos").toInt()
            entry.hasAttribute("offset") -> {
                val number = entry.getAttribute("extnumber").ifEmpty { null }?.toInt() ?: extension ?: return null
                val magnitude = 1_000_000_000L + (number - 1) * 1000L + entry.getAttribute("offset").toLong()
                if (entry.getAttribute("dir") == "-") -magnitude else magnitude
            }
            entry.hasAttribute("value") -> cValue(entry.getAttribute("value"))
            else -> return null
        }
        return Constant(name, type, if (bitwidth == 64) longLiteral(value) else intLiteral(value.toInt()))
    }

    private fun readCommands() {
        for (element in root.child("commands")!!.children("command")) {
            if (!element.supports("api")) continue
            val alias = element.getAttribute("alias")
            if (alias.isNotEmpty()) {
                commandAliases[element.getAttribute("name")] = alias
                continue
            }
            val proto = declaration(element.child("proto")!!)
            val params = element.children("param").filter { it.supports("api") }.map(::declaration)
            fun codes(name: String) = element.getAttribute(name).split(',').filter { it.isNotEmpty() }
            commands[proto.name] = CommandDef(proto.name, proto.type, proto.pointers, params, codes("successcodes"), codes("errorcodes"))
        }
    }

    private fun readRequires(owner: Element, extension: Int?) {
        for (require in owner.children("require")) {
            if (!require.supports("api")) continue
            for (type in require.children("type")) requiredTypes += type.getAttribute("name")
            for (command in require.children("command")) requiredCommands += command.getAttribute("name")
            for (entry in require.children("enum")) {
                if (!entry.supports("api")) continue
                val extends = entry.getAttribute("extends")
                if (extends.isNotEmpty()) {
                    val values = enumValues.getOrPut(extends) { LinkedHashMap() }
                    val constant = enumConstant(entry, enumBitwidth[extends] ?: 32, extension) ?: continue
                    values.putIfAbsent(constant.name, constant)
                    continue
                }
                val name = entry.getAttribute("name")
                if (name in apiConstants || name in extensionConstants) continue
                val value = entry.getAttribute("value")
                extensionConstants[name] = when {
                    entry.hasAttribute("alias") -> Constant(name, "", entry.getAttribute("alias"))
                    value.startsWith("\"") -> Constant(name, "String", value)
                    value.isNotEmpty() && value.first().let { it.isDigit() || it == '-' } -> Constant(name, "Int", intLiteral(cValue(value).toInt()))
                    else -> continue
                }
            }
        }
    }

    companion object {
        private val dimension = Regex("""\[\s*(\w+)\s*]""")
        private val bitfield = Regex(""":\s*(\d+)""")

        fun declaration(element: Element): Declaration {
            val text = StringBuilder()
            for (node in element.childNodes.list()) if (node.nodeName != "comment") text.append(node.textContent)
            val name = element.child("name")!!.textContent
            val beforeName = text.substring(0, text.indexOf(name))
            val afterName = text.substring(text.indexOf(name) + name.length)
            return Declaration(
                name = name,
                type = element.child("type")!!.textContent,
                pointers = text.count { it == '*' },
                dims = dimension.findAll(afterName).map { it.groupValues[1] }.toList(),
                bits = bitfield.find(afterName)?.groupValues?.get(1)?.toInt(),
                values = element.getAttribute("values").ifEmpty { null },
                len = element.getAttribute("len").ifEmpty { null },
                optional = element.getAttribute("optional").startsWith("true"),
                isConst = "const" in beforeName,
            )
        }

        fun intLiteral(value: Int) = if (value == Int.MIN_VALUE) "Int.MIN_VALUE" else value.toString()
        fun longLiteral(value: Long) = if (value == Long.MIN_VALUE) "Long.MIN_VALUE" else "${value}L"
    }
}

fun org.w3c.dom.NodeList.list(): List<Node> = (0 until length).map(::item)
fun Element.children(tag: String): List<Element> = childNodes.list().filterIsInstance<Element>().filter { it.tagName == tag }
fun Element.child(tag: String): Element? = children(tag).firstOrNull()
fun Element.descendant(tag: String): Element? = getElementsByTagName(tag).list().firstOrNull() as Element?
