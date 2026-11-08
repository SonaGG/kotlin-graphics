package gg.sona.graphics.generator

import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.joinToCode
import gg.sona.graphics.generator.metal.Dump
import gg.sona.graphics.generator.metal.MetalGenerator
import java.io.File

fun main(args: Array<String>) {
    when (args[0]) {
        "vulkan" -> VulkanGenerator(Model(Registry(File(args[1]), setOf("win32", "xlib", "xlib_xrandr", "xcb", "wayland", "metal"))), File(args[2])).run()
        "metal" -> MetalGenerator(Dump(args.drop(2).map(::File)), File(args[1])).run()
        else -> error("Unknown binding ${args[0]}")
    }
}

private const val RUNTIME = "gg.sona.graphics.runtime"
private val MEMORY = ClassName(RUNTIME, "Memory")
private val STACK = ClassName(RUNTIME, "MemoryStack")
private val NATIVE_LIBRARY = ClassName(RUNTIME, "NativeLibrary")
private val READ_UTF8 = MemberName(RUNTIME, "readUtf8", isExtension = true)
private val STACK_PROPERTY = MemberName(RUNTIME, "stack")
private val MEMORY_STACK = MemberName(RUNTIME, "memoryStack")
private val JVM_INLINE = ClassName("kotlin.jvm", "JvmInline")
private val LIST = ClassName("kotlin.collections", "List")
private val FUNCTION = MemberName(PACKAGE, "function")
private val CHECK_RESULT = MemberName(PACKAGE, "checkResult")
private val LOAD_TABLE = MemberName(PACKAGE, "loadTable")
private val RESULT = vk("VkResult")

private fun at(offset: Long): CodeBlock = if (offset == 0L) CodeBlock.of("address") else CodeBlock.of("address + %L", offset)

private fun getter(code: CodeBlock) = FunSpec.getterBuilder().addStatement("return %L", code).build()

private fun setter(type: TypeName, vararg statements: CodeBlock) =
    FunSpec.setterBuilder().addParameter("value", type).apply { statements.forEach { addStatement("%L", it) } }.build()

private fun lambda(receiver: TypeName, vararg parameters: ParameterSpec) = LambdaTypeName.get(receiver, parameters.toList(), UNIT)

private fun valueClass(name: String, property: String, type: TypeName) = TypeSpec.classBuilder(name)
    .addModifiers(KModifier.VALUE)
    .addAnnotation(JVM_INLINE)
    .primaryConstructor(FunSpec.constructorBuilder().addParameter(property, type).build())
    .addProperty(PropertySpec.builder(property, type).initializer(property).build())

class VulkanGenerator(private val model: Model, private val output: File) {
    private val registry = model.registry
    private val enumTypes = LinkedHashSet<String>()
    private val flagsTypes = LinkedHashMap<String, String?>()

    private fun read(ty: Ty, at: CodeBlock): CodeBlock = when (ty) {
        is Plain -> CodeBlock.of("%T.get%L(%L)", MEMORY, ty.scalar.access, at)
        is Bool -> CodeBlock.of("%T.getInt(%L) != 0", MEMORY, at)
        is EnumTy -> CodeBlock.of("%T(%T.get%L(%L))", vk(ty.name), MEMORY, ty.scalar.access, at)
        is FlagsTy -> CodeBlock.of("%T(%T.get%L(%L))", vk(ty.name), MEMORY, ty.scalar.access, at)
        is Handle -> if (ty.dispatchable) CodeBlock.of("%T.getLong(%L)", MEMORY, at) else CodeBlock.of("%T(%T.getLong(%L))", vk(ty.name), MEMORY, at)
        is Layout -> CodeBlock.of("%T(%L)", vk(ty.name), at)
    }

    private fun write(ty: Ty, at: CodeBlock, value: CodeBlock): CodeBlock = when (ty) {
        is Plain -> CodeBlock.of("%T.put%L(%L, %L)", MEMORY, ty.scalar.access, at, value)
        is Bool -> CodeBlock.of("%T.putInt(%L, if (%L) 1 else 0)", MEMORY, at, value)
        is EnumTy, is FlagsTy -> CodeBlock.of("%T.put%L(%L, %L.value)", MEMORY, ty.scalar().access, at, value)
        is Handle -> if (ty.dispatchable) CodeBlock.of("%T.putLong(%L, %L)", MEMORY, at, value) else CodeBlock.of("%T.putLong(%L, %L.handle)", MEMORY, at, value)
        is Layout -> CodeBlock.of("%T.copy(%L.address, %L, %T.SIZE)", MEMORY, value, at, vk(ty.name))
    }

    private fun file(sourceSet: String, name: String, build: FileSpec.Builder.() -> Unit) {
        FileSpec.builder(PACKAGE, name)
            .addAnnotation(
                AnnotationSpec.builder(Suppress::class)
                    .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                    .addMember("%S, %S, %S, %S, %S", "unused", "FunctionName", "PropertyName", "ObjectPropertyName", "LocalVariableName")
                    .build()
            )
            .apply(build)
            .build()
            .writeTo(File(output, "$sourceSet/kotlin"))
    }

    fun run() {
        output.deleteRecursively()
        val structs = ArrayList<StructLayout>()
        val handles = LinkedHashSet<String>()
        val aliases = LinkedHashMap<String, String>()

        val commands = registry.requiredCommands.mapNotNull { name ->
            val definition = registry.commands[registry.commandAliases[name] ?: name] ?: return@mapNotNull null
            name to definition
        }.filter { (_, command) -> command.params.all { it.pointers > 0 || model.resolve(it.type) != null } }

        val queue = ArrayDeque(registry.requiredTypes + commands.flatMap { (_, command) -> command.params.map { it.type } })
        val seen = HashSet<String>()
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (!seen.add(name)) continue
            val type = registry.types[name] ?: continue
            if (type.alias != null) {
                if (type.category in setOf("struct", "union", "handle", "enum", "bitmask")) aliases[name] = type.alias
                queue += type.alias
                continue
            }
            when (type.category) {
                "handle" -> handles += name
                "enum" -> if (!model.isFlagBits(name)) enumTypes += name
                "bitmask" -> {
                    val bits = type.element.getAttribute("requires").ifEmpty { type.element.getAttribute("bitvalues") }.ifEmpty { null }
                    flagsTypes[name] = bits
                    if (bits != null) queue += bits
                }
                "struct", "union" -> model.layout(name)?.let { layout ->
                    structs += layout
                    for (field in layout.fields) {
                        when (val ty = field.ty) {
                            is Handle -> queue += ty.name
                            is Layout -> queue += ty.name
                            is EnumTy -> queue += ty.name
                            is FlagsTy -> queue += ty.name
                            else -> {}
                        }
                        if (field.pointer) queue += field.decl.type
                    }
                }
            }
        }
        for ((_, command) in commands) for (param in command.params) when (val ty = model.resolve(param.type)) {
            is EnumTy -> enumTypes += ty.name
            is FlagsTy -> if (ty.name !in flagsTypes) flagsTypes[ty.name] = null
            else -> {}
        }
        enumTypes += "VkResult"
        val structNames = structs.map { it.name }.toSet()
        aliases.entries.removeIf { (_, target) ->
            val canonical = model.canonical(target)
            canonical !in structNames && canonical !in handles && canonical !in enumTypes && canonical !in flagsTypes
        }

        writeEnums()
        writeFlags(aliases)
        writeStructs(structs)
        writeChains(structs, structNames)
        writeHandles(handles, aliases)
        writeConstants()
        writeCommands(commands)
        println("Generated ${structs.size} structs, ${handles.size} handles, ${enumTypes.size} enums, ${flagsTypes.size} flags, ${commands.size} commands")
    }

    private fun writeEnums() = file("commonMain", "Enums") {
        for (type in enumTypes) {
            val values = model.numericValues(type)
            val names = model.valueNames(type, values.map { it.name }, flags = false)
            val cls = vk(type)
            val toString = FunSpec.builder("toString").addModifiers(KModifier.OVERRIDE).returns(STRING).beginControlFlow("return when (value)")
            val printed = HashSet<String>()
            val companion = TypeSpec.companionObjectBuilder()
            for (value in values) {
                val name = names[value.name] ?: continue
                if (printed.add(value.literal)) toString.addStatement("%L -> %S", value.literal, name)
                companion.addProperty(PropertySpec.builder(name, cls).initializer("%T(%L)", cls, value.literal).build())
            }
            toString.addStatement("else -> %P", "$type(\${value})").endControlFlow()
            addType(valueClass(type, "value", INT).addFunction(toString.build()).addType(companion.build()).build())
        }
    }

    private fun writeFlags(aliases: Map<String, String>) = file("commonMain", "Flags") {
        for ((type, bits) in flagsTypes) {
            val wide = registry.types[type]?.element?.child("type")?.textContent == "VkFlags64"
            val scalar = if (wide) LONG else INT
            val zero = if (wide) "0L" else "0"
            val values = bits?.let { model.numericValues(it) }.orEmpty()
            val names = model.valueNames(bits ?: type, values.map { it.name }, flags = true)
            val cls = vk(type)
            val builder = valueClass(type, "value", scalar)
            for ((name, operator, expression) in listOf(
                Triple("or", false, "value or other.value"),
                Triple("and", false, "value and other.value"),
                Triple("plus", true, "value or other.value"),
                Triple("minus", true, "value and other.value.inv()"),
            )) {
                builder.addFunction(
                    FunSpec.builder(name).addModifiers(if (operator) KModifier.OPERATOR else KModifier.INFIX)
                        .addParameter("other", cls).returns(cls).addStatement("return %T($expression)", cls).build()
                )
            }
            builder.addFunction(
                FunSpec.builder("contains").addModifiers(KModifier.OPERATOR).addParameter("other", cls).returns(BOOLEAN)
                    .addStatement("return value and other.value == other.value").build()
            )
            builder.addFunction(FunSpec.builder("isEmpty").returns(BOOLEAN).addStatement("return value == %L", zero).build())
            val toString = FunSpec.builder("toString").addModifiers(KModifier.OVERRIDE).returns(STRING)
                .addStatement("if (value == %L) return %S", zero, "None")
                .addStatement("val parts = ArrayList<String>()")
            for (value in values) names[value.name]?.let { toString.addStatement("if (%N in this && %N.value != %L) parts += %S", it, it, zero, it) }
            toString.addStatement("return if (parts.isEmpty()) %P else parts.joinToString(%S)", "$type(\${value})", " | ")
            builder.addFunction(toString.build())
            val companion = TypeSpec.companionObjectBuilder()
            if ("None" !in names.values) companion.addProperty(PropertySpec.builder("None", cls).initializer("%T(%L)", cls, zero).build())
            for (value in values) names[value.name]?.let { companion.addProperty(PropertySpec.builder(it, cls).initializer("%T(%L)", cls, value.literal).build()) }
            addType(builder.addType(companion.build()).build())
            if (bits != null && bits !in aliases) addTypeAlias(com.squareup.kotlinpoet.TypeAliasSpec.builder(bits, cls).build())
        }
    }

    private fun writeStructs(structs: List<StructLayout>) {
        structs.chunked(80).forEachIndexed { chunk, items ->
            file("commonMain", "Structs$chunk") {
                for (struct in items) {
                    addType(struct(struct))
                    val cls = vk(struct.name)
                    addFunction(
                        FunSpec.builder(struct.name)
                            .addKdoc("Allocates a [%L] on the memory stack and fills it with [block].", struct.name)
                            .addModifiers(KModifier.INLINE)
                            .contextParameter("stack", STACK)
                            .addParameter(ParameterSpec.builder("block", lambda(cls)).defaultValue("{}").build())
                            .returns(cls)
                            .addStatement("return %T.allocate().apply(block)", cls)
                            .build()
                    )
                }
            }
        }
    }

    private fun struct(struct: StructLayout): TypeSpec {
        val cls = vk(struct.name)
        val builder = valueClass(struct.name, "address", LONG)
        val byName = struct.fields.associateBy { it.name }
        val counts = HashMap<String, Field>()
        for (field in struct.fields) {
            val len = field.decl.len?.split(',')?.first() ?: continue
            byName[len]?.let { counts[field.name] = it }
        }
        val countNames = counts.values.map { it.name }.toSet()
        val used = HashSet<String>()
        for (field in struct.fields) {
            val kotlin = model.memberName(field.decl)
            val name = if (used.add(kotlin) && byName[kotlin]?.let { it === field } != false) kotlin else field.name.also { used += it }
            member(builder, field, name, counts[field.name], field.name in countNames)
        }
        builder.addFunction(
            FunSpec.builder("get").addKdoc("Returns the element [index] of an array that starts at this struct.")
                .addModifiers(KModifier.OPERATOR).addParameter("index", INT).returns(cls)
                .addStatement("return %T(address + index.toLong() * SIZE)", cls).build()
        )
        val allocate = FunSpec.builder("allocate").addKdoc("Allocates [count] structs on the memory stack.")
            .contextParameter("stack", STACK)
            .addParameter(ParameterSpec.builder("count", INT).defaultValue("1").build())
            .returns(cls)
            .addStatement("val struct = %T(stack.allocate(SIZE * count, ALIGNMENT))", cls)
        if (struct.sType != null) {
            val sType = model.structureTypes[struct.sType] ?: error("Unknown structure type ${struct.sType}")
            allocate.addStatement("for (i in 0 until count) %T.putInt(struct[i].address, %T.%N.value)", MEMORY, vk("VkStructureType"), sType)
        }
        allocate.addStatement("return struct")
        builder.addType(
            TypeSpec.companionObjectBuilder()
                .addProperty(PropertySpec.builder("SIZE", LONG, KModifier.CONST).initializer("%L", struct.size).build())
                .addProperty(PropertySpec.builder("ALIGNMENT", LONG, KModifier.CONST).initializer("%L", struct.align).build())
                .addProperty(PropertySpec.builder("NULL", cls).initializer("%T(0)", cls).build())
                .addFunction(allocate.build())
                .build()
        )
        return builder.build()
    }

    private fun property(name: String, type: TypeName, get: CodeBlock, vararg set: CodeBlock, context: Boolean = false, kdoc: String? = null) =
        PropertySpec.builder(name, type).apply {
            if (context) contextParameter("stack", STACK)
            if (kdoc != null) addKdoc(kdoc)
            getter(getter(get))
            if (set.isNotEmpty()) {
                mutable(true)
                setter(setter(type, *set))
            }
        }.build()

    private fun member(builder: TypeSpec.Builder, field: Field, name: String, count: Field?, isCount: Boolean) {
        val at = at(field.offset)
        val ty = field.ty
        val decl = field.decl
        val value = CodeBlock.of("value")
        if (decl.name == "sType") {
            builder.addProperty(property("sType", vk("VkStructureType"), CodeBlock.of("%T(%T.getInt(%L))", vk("VkStructureType"), MEMORY, at)))
            return
        }
        if (decl.name == "pNext") {
            builder.addProperty(
                property(
                    "next", LONG, CodeBlock.of("%T.getLong(%L)", MEMORY, at), CodeBlock.of("%T.putLong(%L, value)", MEMORY, at),
                    kdoc = "The address of the next struct in the chain. Use `extend` to add a struct to the chain.",
                )
            )
            return
        }
        if (!field.pointer) {
            ty!!
            if (isCount) return builder.addProperty(property(name, ty.type(), read(ty, at))).let {}
            if (field.bits != null) {
                val mask = "0x" + ((1L shl field.bits) - 1).toString(16)
                builder.addProperty(
                    property(
                        name, INT,
                        CodeBlock.of("(%T.getInt(%L) ushr %L) and %L", MEMORY, at, field.shift, mask),
                        CodeBlock.of("%T.putInt(%L, (%T.getInt(%L) and (%L shl %L).inv()) or ((value and %L) shl %L))", MEMORY, at, MEMORY, at, mask, field.shift, mask, field.shift),
                    )
                )
                return
            }
            if (field.count > 1) {
                val element = CodeBlock.of("%L + index * %LL", at, ty.size)
                if (decl.type == "char") return builder.addProperty(property(name, STRING, CodeBlock.of("%T.%M(%L, %L)", MEMORY, READ_UTF8, at, field.count))).let {}
                if (ty is Layout) return builder.addFunction(
                    FunSpec.builder(name).addParameter("index", INT).returns(vk(ty.name)).addStatement("return %L", read(ty, element)).build()
                ).let {}
                builder.addFunction(FunSpec.builder(name).addParameter("index", INT).returns(ty.type()).addStatement("return %L", read(ty, element)).build())
                if (!(ty is Handle && ty.dispatchable)) {
                    builder.addFunction(FunSpec.builder(name).addParameter("index", INT).addParameter("value", ty.type()).addStatement("%L", write(ty, element, value)).build())
                }
                val array = model.arrayType(ty) ?: return
                val scalar = (ty as Plain).scalar
                builder.addProperty(
                    property(
                        "${name}Array", array,
                        CodeBlock.of("%T(%L) { %T.get%L(%L + it * %LL) }", array, field.count, MEMORY, scalar.access, at, ty.size),
                        CodeBlock.of("for (i in 0 until minOf(value.size, %L)) %T.put%L(%L + i * %LL, value[i])", field.count, MEMORY, scalar.access, at, ty.size),
                    )
                )
                return
            }
            if (name == "apiVersion") {
                val version = vk("VkApiVersion")
                builder.addProperty(property(name, version, CodeBlock.of("%T(%T.getInt(%L))", version, MEMORY, at), CodeBlock.of("%T.putInt(%L, value.value)", MEMORY, at)))
                return
            }
            if (ty is Layout) return builder.addProperty(property(name, vk(ty.name), read(ty, at))).let {}
            val propertyName = if (ty is Handle && ty.dispatchable) "${name}Handle" else name
            builder.addProperty(property(propertyName, ty.type(), read(ty, at), write(ty, at, value)))
            return
        }

        val raw = property(name, LONG, CodeBlock.of("%T.getLong(%L)", MEMORY, at), CodeBlock.of("%T.putLong(%L, value)", MEMORY, at))
        if (ty is Layout && model.layout(ty.name) == null) return builder.addProperty(raw).let {}
        val len = decl.len
        fun setCount(size: String): CodeBlock {
            val counter = count ?: return CodeBlock.of("")
            val sized = if (counter.ty!!.scalar() === INT64) "$size.toLong()" else size
            return write(counter.ty, at(counter.offset), CodeBlock.of(sized))
        }
        if (decl.pointers == 1 && decl.type == "char" && (len == null || len == "null-terminated")) {
            builder.addProperty(
                property(
                    name, STRING.copy(nullable = true),
                    CodeBlock.of("%T.getLong(%L).let { if (it == 0L) null else %T.%M(it) }", MEMORY, at, MEMORY, READ_UTF8),
                    CodeBlock.of("%T.putLong(%L, if (value == null) 0L else stack.utf8(value))", MEMORY, at),
                    context = true,
                )
            )
            return
        }
        if (count == null) {
            if (decl.pointers == 1 && ty is Layout && (len == null || len == "1")) {
                val target = vk(ty.name)
                builder.addProperty(
                    property(
                        name, target.copy(nullable = true),
                        CodeBlock.of("%T.getLong(%L).let { if (it == 0L) null else %T(it) }", MEMORY, at, target),
                        CodeBlock.of("%T.putLong(%L, value?.address ?: 0L)", MEMORY, at),
                        context = true,
                    )
                )
                builder.addFunction(
                    FunSpec.builder(name).addModifiers(KModifier.INLINE).contextParameter("stack", STACK)
                        .addParameter("block", lambda(target))
                        .addStatement("%T.putLong(%L, %T(block).address)", MEMORY, at, target).build()
                )
                return
            }
            if (name == "code" && decl.type == "uint32_t") {
                val size = at(field.offset - 8)
                builder.addProperty(
                    property(
                        "code", ClassName("kotlin", "ByteArray"),
                        CodeBlock.of("ByteArray(%T.getLong(%L).toInt()).also { %T.getBytes(%T.getLong(%L), it) }", MEMORY, size, MEMORY, MEMORY, at),
                        CodeBlock.of("val words = stack.allocate(value.size.toLong(), 4)"),
                        CodeBlock.of("%T.putBytes(words, value)", MEMORY),
                        CodeBlock.of("%T.putLong(%L, value.size.toLong())", MEMORY, size),
                        CodeBlock.of("%T.putLong(%L, words)", MEMORY, at),
                        context = true,
                        kdoc = "The SPIR-V code. Its size is in bytes and is a multiple of 4.",
                    )
                )
                return
            }
            builder.addProperty(raw)
            return
        }
        val countRead = read(count.ty!!, at(count.offset)).let { if (count.ty.scalar() === INT64) CodeBlock.of("%L.toInt()", it) else it }
        if (decl.pointers == 2 && decl.type == "char") {
            builder.addProperty(
                property(
                    name, LIST.parameterizedBy(STRING),
                    CodeBlock.of("List(%L) { %T.%M(%T.getLong(%T.getLong(%L) + it * 8L)) }", countRead, MEMORY, READ_UTF8, MEMORY, MEMORY, at),
                    CodeBlock.of("val array = stack.allocate(value.size * 8L)"),
                    CodeBlock.of("value.forEachIndexed { i, text -> %T.putLong(array + i * 8L, stack.utf8(text)) }", MEMORY),
                    CodeBlock.of("%T.putLong(%L, array)", MEMORY, at),
                    setCount("value.size"),
                    context = true,
                )
            )
            return
        }
        if (decl.pointers != 1 || ty == null) return builder.addProperty(raw).let {}
        val element = CodeBlock.of("%T.getLong(%L) + it * %LL", MEMORY, at, ty.size)
        when {
            ty is Layout -> {
                val target = vk(ty.name)
                builder.addProperty(
                    property(
                        name, LIST.parameterizedBy(target),
                        CodeBlock.of("%T.getLong(%L).let { base -> List(%L) { %T(base + it * %T.SIZE) } }", MEMORY, at, countRead, target, target),
                        CodeBlock.of("val array = %T.allocate(value.size)", target),
                        CodeBlock.of("value.forEachIndexed { i, item -> %T.copy(item.address, array[i].address, %T.SIZE) }", MEMORY, target),
                        CodeBlock.of("%T.putLong(%L, array.address)", MEMORY, at),
                        setCount("value.size"),
                        context = true,
                    )
                )
                builder.addFunction(
                    FunSpec.builder(name).addKdoc("Allocates [count] elements on the memory stack and fills each one with [block].")
                        .addModifiers(KModifier.INLINE).contextParameter("stack", STACK)
                        .addParameter("count", INT)
                        .addParameter("block", lambda(target, ParameterSpec.builder("index", INT).build()))
                        .addStatement("val array = %T.allocate(count)", target)
                        .addStatement("for (i in 0 until count) array[i].block(i)")
                        .addStatement("%T.putLong(%L, array.address)", MEMORY, at)
                        .addStatement("%L", setCount("count"))
                        .build()
                )
            }
            ty is Handle && ty.dispatchable -> builder.addFunction(
                FunSpec.builder(name).contextParameter("stack", STACK)
                    .addParameter("value", LIST.parameterizedBy(vk(ty.name)))
                    .addStatement("val array = stack.allocate(value.size * 8L)")
                    .addStatement("value.forEachIndexed { i, item -> %T.putLong(array + i * 8L, item.handle) }", MEMORY)
                    .addStatement("%T.putLong(%L, array)", MEMORY, at)
                    .addStatement("%L", setCount("value.size"))
                    .build()
            )
            ty is Plain && model.arrayType(ty) != null -> {
                val array = model.arrayType(ty)!!
                builder.addProperty(
                    property(
                        name, array,
                        CodeBlock.of("%T(%L) { %T.get%L(%L) }", array, countRead, MEMORY, ty.scalar.access, element),
                        CodeBlock.of("val array = stack.allocate(value.size * %LL, %LL)", ty.size, ty.align),
                        CodeBlock.of("for (i in value.indices) %T.put%L(array + i * %LL, value[i])", MEMORY, ty.scalar.access, ty.size),
                        CodeBlock.of("%T.putLong(%L, array)", MEMORY, at),
                        setCount("value.size"),
                        context = true,
                    )
                )
            }
            ty is Plain -> builder.addProperty(raw)
            else -> builder.addProperty(
                property(
                    name, LIST.parameterizedBy(ty.type()),
                    CodeBlock.of("List(%L) { %L }", countRead, read(ty, element)),
                    CodeBlock.of("val array = stack.allocate(value.size * %LL, %LL)", ty.size, ty.align),
                    CodeBlock.of("value.forEachIndexed { i, item -> %L }", write(ty, CodeBlock.of("array + i * %LL", ty.size), CodeBlock.of("item"))),
                    CodeBlock.of("%T.putLong(%L, array)", MEMORY, at),
                    setCount("value.size"),
                    context = true,
                )
            )
        }
    }

    private fun writeChains(structs: List<StructLayout>, structNames: Set<String>) = file("commonMain", "Chains") {
        for (struct in structs) for (base in struct.extends) {
            val target = model.canonical(base)
            if (target !in structNames) continue
            addFunction(
                FunSpec.builder("extend").addKdoc("Adds [next] to the chain of this struct.")
                    .addModifiers(KModifier.INFIX).receiver(vk(target))
                    .addParameter("next", vk(struct.name)).returns(vk(target))
                    .addStatement("next.next = this.next")
                    .addStatement("this.next = next.address")
                    .addStatement("return this")
                    .build()
            )
        }
    }

    private fun writeHandles(handles: Set<String>, aliases: Map<String, String>) = file("commonMain", "Handles") {
        for (handle in handles) {
            if (handle in DISPATCHABLE) continue
            val cls = vk(handle)
            addType(
                valueClass(handle, "handle", LONG)
                    .addProperty(PropertySpec.builder("isNull", BOOLEAN).getter(getter(CodeBlock.of("handle == 0L"))).build())
                    .addType(TypeSpec.companionObjectBuilder().addProperty(PropertySpec.builder("NULL", cls).initializer("%T(0)", cls).build()).build())
                    .build()
            )
        }
        for ((alias, target) in aliases) addTypeAlias(com.squareup.kotlinpoet.TypeAliasSpec.builder(alias, vk(target)).build())
    }

    private fun writeConstants() = file("commonMain", "Constants") {
        val constants = TypeSpec.objectBuilder("Vk").addKdoc("The constants of the Vulkan API.")
        for (constant in registry.apiConstants.values) {
            val value = registry.apiConstants[constant.literal]?.literal ?: constant.literal
            val type = when (constant.kotlinType) { "Float" -> com.squareup.kotlinpoet.FLOAT; "Long" -> LONG; else -> INT }
            constants.addProperty(PropertySpec.builder(constant.name.removePrefix("VK_"), type, KModifier.CONST).initializer("%L", value).build())
        }
        addType(constants.build())
        for (constant in registry.extensionConstants.values) {
            if (!constant.name.endsWith("_EXTENSION_NAME") || constant.kotlinType != "String") continue
            val extension = constant.literal.trim('"')
            val parts = extension.split('_')
            if (parts.size < 3) continue
            val vendor = parts[1].let { it[0] + it.substring(1).lowercase() }
            val objectName = vendor + parts.drop(2).joinToString("") { it.replaceFirstChar(Char::uppercase) }
            val spec = registry.extensionConstants[constant.name.removeSuffix("_EXTENSION_NAME") + "_SPEC_VERSION"]
            val type = TypeSpec.objectBuilder(objectName)
                .addProperty(PropertySpec.builder("NAME", STRING, KModifier.CONST).initializer("%S", extension).build())
            if (spec != null && spec.kotlinType == "Int") type.addProperty(PropertySpec.builder("SPEC_VERSION", INT, KModifier.CONST).initializer("%L", spec.literal).build())
            addType(type.build())
        }
    }

    private fun writeCommands(commands: List<Pair<String, CommandDef>>) {
        val signatures = LinkedHashMap<String, Pair<List<Char>, Char>>()
        signatures["JJ_J"] = listOf('J', 'J') to 'J'
        val hidden = setOf("vkGetInstanceProcAddr", "vkGetDeviceProcAddr")
        val names = mapOf("instance" to ArrayList<String>(), "device" to ArrayList(), "global" to ArrayList())
        val methods = LinkedHashMap<String?, MutableList<FunSpec>>()
        for (receiver in listOf(null) + DISPATCHABLE) methods[receiver] = ArrayList()

        for ((name, command) in commands) {
            if (name in hidden) continue
            val receiver = model.receiverOf(command)
            val (table, level) = when (receiver) {
                null -> CodeBlock.of("%T.functions", vk("Vulkan")) to "global"
                "VkInstance" -> CodeBlock.of("functions") to "instance"
                "VkPhysicalDevice" -> CodeBlock.of("instance.functions") to "instance"
                "VkDevice" -> CodeBlock.of("functions") to "device"
                else -> CodeBlock.of("device.functions") to "device"
            }
            val list = names.getValue(level)
            val index = list.size
            list += name
            methods.getValue(receiver) += Method(model, name, command, receiver, table, index).build(signatures)
        }

        fun table(names: List<String>) = CodeBlock.of("arrayOf(%L)", names.map { CodeBlock.of("%S", it) }.joinToCode(", "))
        fun equality(cls: ClassName) = listOf(
            FunSpec.builder("equals").addModifiers(KModifier.OVERRIDE).addParameter("other", ANY.copy(nullable = true)).returns(BOOLEAN)
                .addStatement("return other is %T && other.handle == handle", cls).build(),
            FunSpec.builder("hashCode").addModifiers(KModifier.OVERRIDE).returns(INT).addStatement("return handle.hashCode()").build(),
        )
        fun dispatchable(name: String, kdoc: String, vararg parameters: Pair<String, TypeName>, extra: TypeSpec.Builder.() -> Unit = {}): TypeSpec {
            val cls = vk(name)
            return TypeSpec.classBuilder(name).addKdoc(kdoc)
                .primaryConstructor(FunSpec.constructorBuilder().apply { parameters.forEach { addParameter(it.first, it.second) } }.build())
                .apply { parameters.forEach { addProperty(PropertySpec.builder(it.first, it.second).initializer(it.first).build()) } }
                .apply(extra)
                .addFunctions(equality(cls))
                .addFunctions(methods.getValue(name))
                .build()
        }
        val longArray = ClassName("kotlin", "LongArray")
        val loadInstance = CodeBlock.of(
            "LongArray(INSTANCE_COMMANDS.size).also { table -> %M(table, INSTANCE_COMMANDS) { name -> callJJ_J(%T.getInstanceProcAddr, handle, name) } }",
            LOAD_TABLE, vk("Vulkan"),
        )
        val loadDevice = CodeBlock.of(
            "LongArray(DEVICE_COMMANDS.size).also { table -> %M(table, DEVICE_COMMANDS) { name -> callJJ_J(physicalDevice.instance.getDeviceProcAddr, handle, name) } }",
            LOAD_TABLE,
        )

        file("commonMain", "Dispatch") {
            addProperty(PropertySpec.builder("INSTANCE_COMMANDS", Array::class.asTypeName().parameterizedBy(STRING), KModifier.INTERNAL).initializer(table(names.getValue("instance"))).build())
            addProperty(PropertySpec.builder("DEVICE_COMMANDS", Array::class.asTypeName().parameterizedBy(STRING), KModifier.INTERNAL).initializer(table(names.getValue("device"))).build())
            addProperty(PropertySpec.builder("GLOBAL_COMMANDS", Array::class.asTypeName().parameterizedBy(STRING), KModifier.INTERNAL).initializer(table(names.getValue("global"))).build())
            addType(dispatchable("VkInstance", "A Vulkan instance. It loads the commands of the instance when it is created.", "handle" to LONG) {
                addProperty(PropertySpec.builder("functions", longArray, KModifier.INTERNAL).initializer(loadInstance).build())
                addProperty(
                    PropertySpec.builder("getDeviceProcAddr", LONG, KModifier.INTERNAL)
                        .initializer("%M { callJJ_J(%T.getInstanceProcAddr, handle, %M.utf8(%S)) }", MEMORY_STACK, vk("Vulkan"), STACK_PROPERTY, "vkGetDeviceProcAddr")
                        .build()
                )
            })
            addType(dispatchable("VkPhysicalDevice", "A physical device of a [VkInstance].", "handle" to LONG, "instance" to vk("VkInstance")))
            addType(dispatchable("VkDevice", "A logical device. It loads its device commands when it is created, so they skip the dispatch of the loader.", "handle" to LONG, "physicalDevice" to vk("VkPhysicalDevice")) {
                addProperty(PropertySpec.builder("instance", vk("VkInstance")).getter(getter(CodeBlock.of("physicalDevice.instance"))).build())
                addProperty(PropertySpec.builder("functions", longArray, KModifier.INTERNAL).initializer(loadDevice).build())
            })
            addType(dispatchable("VkQueue", "A queue of a [VkDevice].", "handle" to LONG, "device" to vk("VkDevice")))
            addType(dispatchable("VkCommandBuffer", "A command buffer of a [VkDevice].", "handle" to LONG, "device" to vk("VkDevice")))
            addType(
                TypeSpec.objectBuilder("Vulkan").addKdoc("Loads the Vulkan loader and runs the global commands. Call [load] before any other command.")
                    .addProperty(PropertySpec.builder("getInstanceProcAddr", LONG, KModifier.INTERNAL).mutable(true).initializer("0L").build())
                    .addProperty(PropertySpec.builder("functions", longArray, KModifier.INTERNAL).initializer("LongArray(GLOBAL_COMMANDS.size)").build())
                    .addFunction(
                        FunSpec.builder("load").addKdoc("Loads the Vulkan loader of the system, or [library].")
                            .addParameter(
                                ParameterSpec.builder("library", NATIVE_LIBRARY)
                                    .defaultValue("%T.open(%S, %S, %S, %S, %S)", NATIVE_LIBRARY, "vulkan-1.dll", "libvulkan.so.1", "libvulkan.so", "libvulkan.1.dylib", "libMoltenVK.dylib")
                                    .build()
                            )
                            .addStatement("getInstanceProcAddr = library.symbol(%S)", "vkGetInstanceProcAddr")
                            .addStatement("check(getInstanceProcAddr != 0L) { %S }", "The Vulkan loader has no vkGetInstanceProcAddr")
                            .addStatement("%M(functions, GLOBAL_COMMANDS) { name -> callJJ_J(getInstanceProcAddr, 0L, name) }", LOAD_TABLE)
                            .build()
                    )
                    .addProperty(
                        PropertySpec.builder("instanceVersion", vk("VkApiVersion")).addKdoc("The version of the instance-level API.")
                            .getter(getter(CodeBlock.of("%M { %T(enumerateInstanceVersion()) }", MEMORY_STACK, vk("VkApiVersion")))).build()
                    )
                    .addFunctions(methods.getValue(null))
                    .build()
            )
        }
        writeInvokers(signatures)
    }

    private fun writeInvokers(signatures: Map<String, Pair<List<Char>, Char>>) {
        fun kotlinOf(kind: Char): TypeName = when (kind) {
            'B' -> com.squareup.kotlinpoet.BYTE; 'S' -> com.squareup.kotlinpoet.SHORT; 'I' -> INT; 'J' -> LONG
            'F' -> com.squareup.kotlinpoet.FLOAT; 'D' -> com.squareup.kotlinpoet.DOUBLE; else -> UNIT
        }
        fun layoutOf(kind: Char) = MemberName(ClassName("java.lang.foreign", "ValueLayout"), when (kind) {
            'B' -> "JAVA_BYTE"; 'S' -> "JAVA_SHORT"; 'I' -> "JAVA_INT"; 'J' -> "JAVA_LONG"; 'F' -> "JAVA_FLOAT"; else -> "JAVA_DOUBLE"
        })
        fun header(signature: String, params: List<Char>, ret: Char, vararg modifiers: KModifier) = FunSpec.builder("call$signature")
            .addModifiers(KModifier.INTERNAL, *modifiers)
            .addParameter("function", LONG)
            .apply { params.forEachIndexed { i, kind -> addParameter("a$i", kotlinOf(kind)) } }
            .returns(kotlinOf(ret))

        val descriptor = ClassName("java.lang.foreign", "FunctionDescriptor")
        val segment = ClassName("java.lang.foreign", "MemorySegment")
        val handle = ClassName("java.lang.invoke", "MethodHandle")
        val cFunction = ClassName("kotlinx.cinterop", "CFunction")
        val toCPointer = MemberName("kotlinx.cinterop", "toCPointer", isExtension = true)
        val invoke = MemberName("kotlinx.cinterop", "invoke", isExtension = true)

        file("commonMain", "Invokers") {
            for ((signature, shape) in signatures) addFunction(header(signature, shape.first, shape.second, KModifier.EXPECT).build())
        }
        file("jvmMain", "Invokers.jvm") {
            addProperty(PropertySpec.builder("linker", ClassName("java.lang.foreign", "Linker"), KModifier.PRIVATE).initializer("%T.nativeLinker()", ClassName("java.lang.foreign", "Linker")).build())
            for ((signature, shape) in signatures) {
                val (params, ret) = shape
                val layouts = params.map { CodeBlock.of("%M", layoutOf(it)) }
                val create = if (ret == 'V') CodeBlock.of("%T.ofVoid(%L)", descriptor, layouts.joinToCode(", "))
                else CodeBlock.of("%T.of(%L)", descriptor, (listOf(CodeBlock.of("%M", layoutOf(ret))) + layouts).joinToCode(", "))
                addProperty(PropertySpec.builder("handle$signature", handle, KModifier.PRIVATE).initializer("linker.downcallHandle(%L)", create).build())
                val arguments = (listOf(CodeBlock.of("%T.ofAddress(function)", segment)) + params.indices.map { CodeBlock.of("a$it") }).joinToCode(", ")
                val call = CodeBlock.of("handle$signature.invokeExact(%L)", arguments)
                addFunction(
                    header(signature, params, ret, KModifier.ACTUAL)
                        .apply { if (ret == 'V') addStatement("%L", call) else addStatement("return %L as %T", call, kotlinOf(ret)) }
                        .build()
                )
            }
        }
        file("nativeMain", "Invokers.native") {
            for ((signature, shape) in signatures) {
                val (params, ret) = shape
                val type = cFunction.parameterizedBy(LambdaTypeName.get(null, params.map { ParameterSpec.unnamed(kotlinOf(it)) }, kotlinOf(ret)))
                val call = CodeBlock.of("function.%M<%T>()!!.%M(%L)", toCPointer, type, invoke, params.indices.map { CodeBlock.of("a$it") }.joinToCode(", "))
                addFunction(header(signature, params, ret, KModifier.ACTUAL).apply { if (ret == 'V') addStatement("%L", call) else addStatement("return %L", call) }.build())
            }
        }
    }
}

private class Method(
    private val model: Model,
    private val cName: String,
    private val command: CommandDef,
    private val receiver: String?,
    private val table: CodeBlock,
    private val index: Int,
) {
    private class Arg(val decl: Declaration, val ty: Ty?) {
        val name get() = decl.name
    }

    private val args = command.params.drop(if (receiver != null) 1 else 0).map { Arg(it, model.resolve(it.type)) }

    private fun lengthOf(arg: Arg) = arg.decl.len?.split(',')?.first()

    private fun isArrayInput(arg: Arg): Boolean {
        val ty = arg.ty ?: return false
        val len = lengthOf(arg) ?: return false
        return arg.decl.pointers == 1 && arg.decl.isConst && arg.decl.type != "char" && len in args.map { it.name } &&
            (ty !is Plain || model.arrayType(ty) != null)
    }

    private fun name(arg: Arg) = model.memberName(arg.decl)

    private fun read(ty: Ty, at: String): CodeBlock = when (ty) {
        is Plain -> CodeBlock.of("%T.get%L(%L)", MEMORY, ty.scalar.access, at)
        is Bool -> CodeBlock.of("%T.getInt(%L) != 0", MEMORY, at)
        is EnumTy, is FlagsTy -> CodeBlock.of("%T(%T.get%L(%L))", ty.type(), MEMORY, ty.scalar().access, at)
        is Handle -> construct(ty, CodeBlock.of("%T.getLong(%L)", MEMORY, at))
        is Layout -> CodeBlock.of("%T(%L)", vk(ty.name), at)
    }

    private fun construct(ty: Handle, raw: CodeBlock): CodeBlock = when (ty.name) {
        "VkPhysicalDevice" -> CodeBlock.of("%T(%L, %L)", vk(ty.name), raw, if (receiver == "VkInstance") "this" else "instance")
        "VkDevice" -> CodeBlock.of("%T(%L, this)", vk(ty.name), raw)
        "VkQueue", "VkCommandBuffer" -> CodeBlock.of("%T(%L, %L)", vk(ty.name), raw, if (receiver == "VkDevice") "this" else "device")
        else -> CodeBlock.of("%T(%L)", vk(ty.name), raw)
    }

    private fun handleType(ty: Handle) = vk(ty.name)

    fun build(signatures: MutableMap<String, Pair<List<Char>, Char>>): List<FunSpec> {
        val kinds = ArrayList<Char>()
        val params = ArrayList<ParameterSpec>()
        val before = ArrayList<CodeBlock>()
        val pass = ArrayList<CodeBlock>()
        var result: CodeBlock? = null
        var resultType: TypeName = UNIT
        var needsStack = false
        if (receiver != null) {
            kinds += 'J'
            pass += CodeBlock.of("handle")
        }
        val extraSuccess = command.successCodes.any { it != "VK_SUCCESS" && it != "VK_INCOMPLETE" }
        val enumerate = args.size >= 2 && args.last().let { last ->
            val countArg = args[args.size - 2]
            last.decl.len == countArg.name && !last.decl.isConst && countArg.decl.pointers == 1 && !countArg.decl.isConst
        }
        val outIndex = if (enumerate || extraSuccess || args.isEmpty()) -1 else args.lastIndex.takeIf {
            val arg = args.last()
            val ty = arg.ty
            !arg.decl.isConst && (
                arg.decl.pointers == 2 && arg.decl.type == "void" ||
                    arg.decl.pointers == 1 && arg.decl.len == null &&
                    (ty is Plain || ty is Bool || ty is EnumTy || ty is FlagsTy || ty is Handle || ty is Layout && model.layout(ty.name)?.sType == null) ||
                    arg.decl.pointers == 1 && ty is Handle && lengthOf(arg)!!.let { it in args.map { a -> a.name } || "->" in it }
                )
        } ?: -1

        for ((i, arg) in args.withIndex()) {
            val decl = arg.decl
            val ty = arg.ty
            val name = name(arg)
            when {
                enumerate && i >= args.size - 2 -> {
                    kinds += 'J'
                    pass += CodeBlock.of(if (i == args.lastIndex) "array" else "count")
                }
                i == outIndex -> {
                    kinds += 'J'
                    needsStack = true
                    if (decl.len != null) {
                        ty as Handle
                        val len = lengthOf(arg)!!
                        val countExpr = if ("->" in len) {
                            val (struct, member) = len.split("->")
                            CodeBlock.of("%N.%N", name(args.first { it.name == struct }), member)
                        } else CodeBlock.of("%N.size", name(args.first { it !== arg && isArrayInput(it) && lengthOf(it) == len }))
                        before += CodeBlock.of("val count = %L", countExpr)
                        before += CodeBlock.of("val out = stack.allocate(count * 8L)")
                        result = CodeBlock.of("List(count) { %L }", construct(ty, CodeBlock.of("%T.getLong(out + it * 8L)", MEMORY)))
                        resultType = LIST.parameterizedBy(handleType(ty))
                    } else {
                        val memory = if (decl.pointers == 2) POINTER_TY else ty!!
                        before += if (memory is Layout) CodeBlock.of("val out = %T.allocate().address", vk(memory.name)) else CodeBlock.of("val out = stack.allocate(%LL)", memory.size)
                        result = read(memory, "out")
                        resultType = if (memory is Handle) handleType(memory) else memory.type()
                    }
                    pass += CodeBlock.of("out")
                }
                decl.pointers == 0 && args.any { isArrayInput(it) && lengthOf(it) == decl.name } -> {
                    val first = args.first { isArrayInput(it) && lengthOf(it) == decl.name }
                    val scalar = ty!!.scalar()
                    kinds += scalar.kind
                    pass += if (scalar === INT64) CodeBlock.of("%N.size.toLong()", name(first)) else CodeBlock.of("%N.size", name(first))
                }
                decl.pointers == 0 -> {
                    ty!!
                    kinds += ty.scalar().kind
                    val type = if (ty is Handle) handleType(ty) else ty.type()
                    val default = when {
                        ty is Handle && !ty.dispatchable && decl.optional && i > 0 -> CodeBlock.of("%T.NULL", type)
                        ty is FlagsTy && decl.optional -> CodeBlock.of("%T.None", type)
                        ty is Plain && decl.name == "instanceCount" -> CodeBlock.of("1")
                        ty is Plain && ty.scalar === INT32 && decl.name in setOf("firstVertex", "firstInstance", "firstIndex", "vertexOffset") -> CodeBlock.of("0")
                        else -> null
                    }
                    params += ParameterSpec.builder(name, type).apply { if (default != null) defaultValue(default) }.build()
                    pass += when (ty) {
                        is Bool -> CodeBlock.of("if (%N) 1 else 0", name)
                        is EnumTy, is FlagsTy -> CodeBlock.of("%N.value", name)
                        is Handle -> CodeBlock.of("%N.handle", name)
                        else -> CodeBlock.of("%N", name)
                    }
                }
                decl.type == "char" && decl.pointers == 1 && decl.isConst -> {
                    kinds += 'J'
                    needsStack = true
                    params += ParameterSpec.builder(name, STRING.copy(nullable = true)).apply { if (decl.optional) defaultValue("null") }.build()
                    pass += CodeBlock.of("if (%N == null) 0L else stack.utf8(%N)", name, name)
                }
                decl.pointers == 1 && decl.isConst && ty is Layout && decl.len == null -> {
                    kinds += 'J'
                    params += ParameterSpec.builder(name, vk(ty.name)).apply { if (decl.optional) defaultValue("%T.NULL", vk(ty.name)) }.build()
                    pass += CodeBlock.of("%N.address", name)
                }
                isArrayInput(arg) -> {
                    kinds += 'J'
                    needsStack = true
                    ty!!
                    val array = "${name}Array"
                    when {
                        ty is Layout -> {
                            params += ParameterSpec.builder(name, LIST.parameterizedBy(vk(ty.name))).build()
                            before += CodeBlock.of("val %N = %T.allocate(%N.size)", array, vk(ty.name), name)
                            before += CodeBlock.of("%N.forEachIndexed { i, item -> %T.copy(item.address, %N[i].address, %T.SIZE) }", name, MEMORY, array, vk(ty.name))
                            pass += CodeBlock.of("%N.address", array)
                        }
                        ty is Plain -> {
                            params += ParameterSpec.builder(name, model.arrayType(ty)!!).build()
                            before += CodeBlock.of("val %N = stack.allocate(%N.size * %LL, %LL)", array, name, ty.size, ty.align)
                            before += CodeBlock.of("for (i in %N.indices) %T.put%L(%N + i * %LL, %N[i])", name, MEMORY, ty.scalar.access, array, ty.size, name)
                            pass += CodeBlock.of("%N", array)
                        }
                        else -> {
                            val type = if (ty is Handle) handleType(ty) else ty.type()
                            params += ParameterSpec.builder(name, LIST.parameterizedBy(type)).build()
                            val item = when (ty) {
                                is Handle -> CodeBlock.of("%T.putLong(%N + i * 8L, item.handle)", MEMORY, array)
                                is Bool -> CodeBlock.of("%T.putInt(%N + i * 4L, if (item) 1 else 0)", MEMORY, array)
                                else -> CodeBlock.of("%T.put%L(%N + i * %LL, item.value)", MEMORY, ty.scalar().access, array, ty.size)
                            }
                            before += CodeBlock.of("val %N = stack.allocate(%N.size * %LL, %LL)", array, name, ty.size, ty.align)
                            before += CodeBlock.of("%N.forEachIndexed { i, item -> %L }", name, item)
                            pass += CodeBlock.of("%N", array)
                        }
                    }
                }
                decl.pointers == 1 && !decl.isConst && ty is Layout && decl.len == null -> {
                    kinds += 'J'
                    params += ParameterSpec.builder(name, vk(ty.name)).build()
                    pass += CodeBlock.of("%N.address", name)
                }
                else -> {
                    kinds += 'J'
                    params += ParameterSpec.builder(name, LONG).build()
                    pass += CodeBlock.of("%N", name)
                }
            }
        }

        val ret = if (command.returnPointers > 0) POINTER_TY else if (command.returnType == "void") null else model.resolve(command.returnType)
        val returnKind = ret?.scalar()?.kind ?: 'V'
        val signature = kinds.joinToString("") + "_" + returnKind
        signatures[signature] = kinds.toList() to returnKind
        val isResult = command.returnType == "VkResult"
        val call = CodeBlock.of("call$signature(%L)", (listOf(CodeBlock.of("%M(%L, %L, %S)", FUNCTION, table, index, cName)) + pass).joinToCode(", "))
        val methodName = model.methodName(cName, receiver)
        val destroys = args.size in 1..2 && (methodName.startsWith("destroy") || methodName.startsWith("free")) &&
            args.first().let { it.decl.pointers == 0 && it.ty is Handle && methodName.removePrefix("destroy").removePrefix("free") == model.canonical(it.decl.type).removePrefix("Vk") }
        val kotlinName = if (destroys) methodName.takeWhile { it.isLowerCase() } else methodName

        val method = FunSpec.builder(kotlinName).addParameters(params)

        if (enumerate) {
            needsStack = true
            val countArg = args[args.size - 2]
            val ty = args.last().ty
            val countRead = if ((countArg.ty as? Plain)?.scalar === INT64) CodeBlock.of("%T.getLong(count).toInt()", MEMORY) else CodeBlock.of("%T.getInt(count)", MEMORY)
            val (elementType, allocate, element) = when (ty) {
                is Layout -> Triple(vk(ty.name), CodeBlock.of("%T.allocate(maxOf(size, 1)).address", vk(ty.name)), CodeBlock.of("%T(array + it * %T.SIZE)", vk(ty.name), vk(ty.name)))
                null -> Triple(com.squareup.kotlinpoet.BYTE, CodeBlock.of("stack.allocate(maxOf(size, 1).toLong())"), CodeBlock.of("%T.getByte(array + it)", MEMORY))
                else -> Triple(if (ty is Handle) handleType(ty) else ty.type(), CodeBlock.of("stack.allocate(maxOf(size, 1) * %LL)", ty.size), read(ty, "array + it * ${ty.size}L"))
            }
            val first = CodeBlock.of("call$signature(%L)", (listOf(CodeBlock.of("%M(%L, %L, %S)", FUNCTION, table, index, cName)) + pass.dropLast(1) + CodeBlock.of("0L")).joinToCode(", "))
            method.addStatement("val count = stack.allocate(8)")
            method.addStatement("var array: Long")
            method.addStatement("var size: Int")
            if (isResult) {
                method.beginControlFlow("while (true)")
                    .addStatement("%M(%L, %S)", CHECK_RESULT, first, cName)
                    .addStatement("size = %L", countRead)
                    .addStatement("array = %L", allocate)
                    .addStatement("if (%M(%L, %S) != %T.Incomplete.value) break", CHECK_RESULT, call, cName, RESULT)
                    .endControlFlow()
            } else {
                method.addStatement("%L", first).addStatement("size = %L", countRead).addStatement("array = %L", allocate).addStatement("%L", call)
            }
            method.addStatement("return List(%L) { %L }", countRead, element)
            method.returns(LIST.parameterizedBy(elementType))
            method.contextParameter("stack", STACK)
            return listOf(method.build())
        }

        val returns: TypeName = when {
            isResult && extraSuccess -> RESULT
            result != null -> resultType
            ret == null || isResult -> UNIT
            ret is Handle -> handleType(ret)
            else -> ret.type()
        }
        if (needsStack) method.contextParameter("stack", STACK)
        if (returns != UNIT) method.returns(returns)
        before.forEach { method.addStatement("%L", it) }
        when {
            isResult && extraSuccess -> method.addStatement("return %T(%M(%L, %S))", RESULT, CHECK_RESULT, call, cName)
            isResult -> method.addStatement("%M(%L, %S)", CHECK_RESULT, call, cName)
            ret == null -> method.addStatement("%L", call)
            result == null -> method.addStatement(
                "return %L", when (ret) {
                    is Bool -> CodeBlock.of("%L != 0", call)
                    is EnumTy, is FlagsTy -> CodeBlock.of("%T(%L)", ret.type(), call)
                    else -> call
                }
            )
            else -> method.addStatement("%L", call)
        }
        if (result != null) method.addStatement("return %L", result)
        val methods = mutableListOf(method.build())

        val info = args.firstOrNull()?.takeIf { it.decl.pointers == 1 && it.decl.isConst && it.ty is Layout && it.decl.len == null && !it.decl.optional }
        if (info != null && params.isNotEmpty() && params.drop(1).all { it.defaultValue != null }) {
            val type = vk((info.ty as Layout).name)
            val rest = params.drop(1)
            methods += FunSpec.builder(kotlinName)
                .addModifiers(KModifier.INLINE)
                .contextParameter("stack", STACK)
                .addParameters(rest)
                .addParameter("block", lambda(type))
                .apply { if (returns != UNIT) returns(returns) }
                .addStatement(
                    "${if (returns != UNIT) "return " else ""}%N(%L)", kotlinName,
                    (listOf(CodeBlock.of("%T(block)", type)) + rest.map { CodeBlock.of("%N", it.name) }).joinToCode(", "),
                )
                .build()
        }
        return methods
    }
}
