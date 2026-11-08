package gg.sona.graphics.generator.metal

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.BYTE
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.DOUBLE
import com.squareup.kotlinpoet.FLOAT
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
import com.squareup.kotlinpoet.SHORT
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.WildcardTypeName
import com.squareup.kotlinpoet.joinToCode
import java.io.File

private const val PACKAGE = "gg.sona.graphics.metal"
private const val RUNTIME = "gg.sona.graphics.runtime"
private const val CINTEROP = "kotlinx.cinterop"
private val MEMORY = ClassName(RUNTIME, "Memory")
private val STACK = ClassName(RUNTIME, "MemoryStack")
private val JVM_INLINE = ClassName("kotlin.jvm", "JvmInline")
private val OBJC = ClassName(PACKAGE, "ObjC")
private val SELECTORS = ClassName(PACKAGE, "Selectors")
private val CLASSES = ClassName(PACKAGE, "Classes")
private val NS_OBJECT = ClassName(PACKAGE, "NSObject")
private val EXCEPTION = ClassName(PACKAGE, "MetalException")
private val C_POINTED = ClassName(CINTEROP, "CPointed")
private val TO_C_POINTER = MemberName(CINTEROP, "toCPointer", isExtension = true)
private val TO_LONG = MemberName(CINTEROP, "toLong", isExtension = true)
private val RAW_VALUE = MemberName(CINTEROP, "rawValue", isExtension = true)
private val POINTED = MemberName(CINTEROP, "pointed", isExtension = true)
private val READ_VALUE = MemberName(CINTEROP, "readValue", isExtension = true)
private val PLACE = MemberName(CINTEROP, "place", isExtension = true)
private val OBJC_PTR = MemberName(CINTEROP, "objcPtr", isExtension = true)
private val INTERPRET_OBJC = MemberName(CINTEROP, "interpretObjCPointerOrNull")
private val INTERPRET_C = MemberName(CINTEROP, "interpretCPointer")
private val MEM_SCOPED = MemberName(CINTEROP, "memScoped")
private val ALLOC = MemberName(CINTEROP, "alloc", isExtension = true)
private val PTR = MemberName(CINTEROP, "ptr", isExtension = true)
private val VALUE = MemberName(CINTEROP, "value", isExtension = true)
private val OBJC_OBJECT_VAR = ClassName(CINTEROP, "ObjCObjectVar")
private val NS_ERROR = ClassName("platform.Foundation", "NSError")
private val BRIDGING_RETAIN = MemberName("platform.Foundation", "CFBridgingRetain")
private val CF_RETAIN = MemberName("platform.CoreFoundation", "CFRetain")
private val CF_RELEASE = MemberName("platform.CoreFoundation", "CFRelease")

private fun metal(name: String) = ClassName(PACKAGE, name)

private sealed class Ref
private class Prim(val kind: Char, val type: TypeName, val access: String, val size: Long) : Ref()
private object BoolRef : Ref()
private class EnumRef(val name: String, val prim: Prim, val kotlinEnum: Boolean = false, val unsigned: String? = null) : Ref()
private class ObjRef(val name: String) : Ref()
private object AnyObj : Ref()
private class StringRef(val nullable: Boolean) : Ref()
private class StructRef(val name: String) : Ref()
private class StructPointer(val name: String) : Ref()
private object PointerRef : Ref()
private object ErrorRef : Ref()

private val BYTE_PRIM = Prim('B', BYTE, "Byte", 1)
private val SHORT_PRIM = Prim('S', SHORT, "Short", 2)
private val INT_PRIM = Prim('I', INT, "Int", 4)
private val LONG_PRIM = Prim('J', LONG, "Long", 8)
private val FLOAT_PRIM = Prim('F', FLOAT, "Float", 4)
private val DOUBLE_PRIM = Prim('D', DOUBLE, "Double", 8)

private val PRIMS = mapOf(
    "kotlin/ULong" to LONG_PRIM, "kotlin/Long" to LONG_PRIM,
    "kotlin/UInt" to INT_PRIM, "kotlin/Int" to INT_PRIM,
    "kotlin/UShort" to SHORT_PRIM, "kotlin/Short" to SHORT_PRIM,
    "kotlin/UByte" to BYTE_PRIM, "kotlin/Byte" to BYTE_PRIM,
    "kotlin/Float" to FLOAT_PRIM, "kotlin/Double" to DOUBLE_PRIM,
)

private val ACTIONS = listOf("new", "copy", "next", "make", "alloc", "commandBuffer")
private val OWNED = listOf("new", "alloc", "copy", "mutableCopy")

private class Member(
    val name: String,
    val method: DumpMethod,
    val setter: DumpMethod?,
    val property: Boolean,
    val params: List<Pair<DumpParam, Ref>>,
    val ret: Ref?,
) {
    val error = params.any { it.second === ErrorRef }
    val needsStack = error || ret is StructRef
    val owned = OWNED.any { method.selector.startsWith(it) }
}

private enum class Target { EXPECT, JVM, APPLE, STUB }

class MetalGenerator(private val dump: Dump, private val output: File) {
    private val classes = dump.classes.filter { (source, _) ->
        source.startsWith("platform/Metal/") || source.startsWith("platform/QuartzCore/CAMetal")
    }
    private val wrappers = classes.keys.associateWith { wrapperName(it) }
    private val sourceOf = wrappers.entries.associate { (source, name) -> name to source }
    private val enumTypedefs = dump.constants.mapNotNull { constant ->
        constant.typedef?.takeIf { it.startsWith("platform/Metal/") || it.startsWith("platform/QuartzCore/") }
    }.toSet() + dump.enumClasses
    private val structs = LinkedHashMap<String, DumpStruct>()
    private val signatures = LinkedHashMap<String, Pair<List<String>, String>>()
    private val selectors = LinkedHashMap<String, String>()
    private val classObjects = LinkedHashSet<String>()

    init {
        for ((source, struct) in dump.structs) if (struct.size > 0 && struct.fields.all { fieldRef(it.type) != null }) structs[shortName(source)] = struct
        for ((params, ret) in listOf(listOf("J", "J") to "J", listOf("J", "J") to "V", listOf("J", "J", "J") to "J", listOf("J") to "J")) signature(params, ret)
    }

    private fun shortName(source: String) = source.substringAfterLast('/')

    private fun wrapperName(source: String) = shortName(source).removeSuffix("Protocol")

    private fun fieldRef(type: String): Ref? {
        val base = Dump.baseOf(type)
        PRIMS[base]?.let { prim ->
            val typedef = Dump.typedefOf(type)
            return if (typedef != null && typedef in enumTypedefs) EnumRef(shortName(typedef), prim) else prim
        }
        if (base == "kotlin/Boolean") return BoolRef
        if (dump.structs.containsKey(base)) return StructRef(shortName(base))
        return null
    }

    private fun ref(type: String): Ref? {
        val base = Dump.baseOf(type).removeSuffix("?")
        if (base in dump.enumClasses) {
            val kind = dump.constants.firstOrNull { it.typedef == base }?.base ?: "kotlin/Int"
            return EnumRef(shortName(base), PRIMS[kind] ?: INT_PRIM, kotlinEnum = true, unsigned = kind.takeIf { it.startsWith("kotlin/U") }?.removePrefix("kotlin/"))
        }
        PRIMS[base]?.let { prim ->
            val typedef = Dump.typedefOf(type)
            return if (typedef != null && typedef in enumTypedefs) EnumRef(shortName(typedef), prim) else prim
        }
        return when {
            base == "kotlin/Boolean" -> BoolRef
            base == "kotlin/String" -> StringRef(Dump.baseOf(type).endsWith("?"))
            base.startsWith("kotlin/Function") -> null
            base.startsWith("kotlin/collections/") -> AnyObj
            base.startsWith("kotlinx/cinterop/CValue<") -> shortName(base.removePrefix("kotlinx/cinterop/CValue<").removeSuffix(">")).takeIf { it in structs }?.let(::StructRef)
            base.startsWith("kotlinx/cinterop/CPointer<kotlinx/cinterop/ObjCObjectVar<platform/Foundation/NSError") -> ErrorRef
            base.startsWith("kotlinx/cinterop/CPointer<kotlinx/cinterop/ObjCObjectVar") -> null
            base.startsWith("kotlinx/cinterop/CPointer<") -> {
                val target = base.removePrefix("kotlinx/cinterop/CPointer<").removeSuffix(">")
                shortName(target).takeIf { it in structs }?.let(::StructPointer) ?: PointerRef
            }
            base.startsWith("kotlinx/cinterop/COpaquePointer") -> PointerRef
            base.startsWith("objcnames/") || base.startsWith("platform/Metal/") || base.startsWith("platform/QuartzCore/") -> {
                val name = wrapperName(base)
                if (name in sourceOf) ObjRef(name) else AnyObj
            }
            base.startsWith("platform/") || base.startsWith("kotlinx/cinterop/ObjC") -> AnyObj
            else -> null
        }
    }

    private fun Ref.token(): String = when (this) {
        is Prim -> kind.toString()
        BoolRef -> "B"
        is EnumRef -> prim.kind.toString()
        is StructRef -> "T$name"
        else -> "J"
    }

    private fun Ref.kotlin(): TypeName = when (this) {
        is Prim -> type
        BoolRef -> BOOLEAN
        is EnumRef -> metal(name)
        is ObjRef -> metal(name)
        AnyObj -> NS_OBJECT
        is StringRef -> STRING.copy(nullable = nullable)
        is StructRef -> metal(name)
        is StructPointer -> metal(name)
        PointerRef -> LONG
        ErrorRef -> error("An error slot has no Kotlin type")
    }

    private fun signature(params: List<String>, ret: String): String {
        val name = "call" + params.joinToString("") { if (it.length == 1) it else "_${it}_" } + "_" + if (ret.length == 1) ret else "_${ret}_"
        signatures[name] = params to ret
        return name
    }

    private fun selector(name: String) = selectors.getOrPut(name) { "s" + selectors.size }

    private fun file(sourceSet: String, name: String, build: FileSpec.Builder.() -> Unit) {
        FileSpec.builder(PACKAGE, name)
            .addAnnotation(
                AnnotationSpec.builder(Suppress::class).useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                    .addMember("%S, %S, %S, %S", "unused", "FunctionName", "PropertyName", "UNCHECKED_CAST").build()
            )
            .apply {
                if (sourceSet == "appleMain") addAnnotation(
                    AnnotationSpec.builder(ClassName("kotlin", "OptIn")).useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                        .addMember("%T::class, %T::class", ClassName(CINTEROP, "ExperimentalForeignApi"), ClassName(CINTEROP, "BetaInteropApi")).build()
                )
            }
            .apply(build)
            .build()
            .let { spec ->
                val text = StringBuilder().also(spec::writeTo).toString()
                    .replace(Regex("""expect class (\w+)\(\s*id: Long,\s*\)"""), "expect value class $1(public val id: Long)")
                File(output, "$sourceSet/kotlin/${PACKAGE.replace('.', '/')}/$name.kt").apply { parentFile.mkdirs() }.writeText(text)
            }
    }

    fun run() {
        output.deleteRecursively()
        val members = classes.mapValues { (source, cls) -> members(source, cls) }
        writeEnums()
        writeStructs()
        writeObjects(members)
        writeFunctions()
        writeTables()
        writeInvokers()
        println("Generated ${classes.size} classes, ${structs.size} structs, ${signatures.size} signatures, ${selectors.size} selectors")
    }

    private fun members(source: String, cls: DumpClass): Pair<List<Member>, List<Member>> {
        val methods = (listOf(cls) + ancestors(source).mapNotNull { classes[it] }).flatMap { it.methods }.distinctBy { it.selector }
        return select(methods, instance = true) to if (cls.protocol) emptyList() else select(cls.classMethods, instance = false)
    }

    private fun resolve(method: DumpMethod): Pair<List<Pair<DumpParam, Ref>>, Ref?>? {
        val params = method.params.map { it to (ref(it.type) ?: return null) }
        val ret = if (Dump.baseOf(method.returns) == "kotlin/Unit") null else ref(method.returns) ?: return null
        return params to ret
    }

    private fun select(methods: List<DumpMethod>, instance: Boolean): List<Member> {
        val result = ArrayList<Member>()
        val bySelector = methods.associateBy { it.selector }
        val consumed = HashSet<String>()
        if (instance) for (method in methods) {
            if (method.params.isNotEmpty() || method.selector.startsWith("init")) continue
            val (_, ret) = resolve(method) ?: continue
            if (ret == null || ret is StructRef) continue
            val base = if (method.name.startsWith("is") && method.name.length > 2 && method.name[2].isUpperCase()) method.name.drop(2) else method.name.replaceFirstChar { it.uppercase() }
            val setter = bySelector["set$base:"]?.takeIf { s -> s.params.size == 1 && ref(s.params[0].type)?.let { it.kotlin() == ret.kotlin() } == true }
            if (setter == null && (ACTIONS.any { method.name.startsWith(it) } || method.name.endsWith("Encoder"))) continue
            result += Member(method.name, method, setter, property = true, params = emptyList(), ret = ret)
            consumed += method.selector
            setter?.let { consumed += it.selector }
        }
        val keys = HashSet<String>()
        for (method in methods) {
            if (method.selector in consumed || method.selector.startsWith("init")) continue
            val (params, ret) = resolve(method) ?: continue
            val key = method.name + params.filter { it.second !== ErrorRef }.joinToString { it.second.kotlin().toString() }
            if (result.any { it.property && it.name == method.name && params.isEmpty() }) continue
            if (keys.add(key)) result += Member(method.name, method, null, property = false, params = params, ret = ret)
        }
        return result
    }

    private fun ancestors(source: String, seen: MutableSet<String> = LinkedHashSet()): Set<String> {
        val cls = classes[source] ?: return seen
        for (parent in cls.supers) {
            val canonical = sourceOf[wrapperName(parent)] ?: continue
            if (canonical != source && seen.add(canonical)) ancestors(canonical, seen)
        }
        return seen
    }

    private fun writeEnums() = file("commonMain", "Enums") {
        val groups = dump.constants.filter { it.typedef in enumTypedefs }.groupBy { it.typedef!! }
        for ((typedef, values) in groups) {
            val name = shortName(typedef)
            val prim = PRIMS[values.first().base] ?: continue
            val cls = metal(name)
            val builder = TypeSpec.classBuilder(name).addModifiers(KModifier.VALUE).addAnnotation(JVM_INLINE)
                .primaryConstructor(FunSpec.constructorBuilder().addParameter("value", prim.type).build())
                .addProperty(PropertySpec.builder("value", prim.type).initializer("value").build())
            val flags = name.endsWith("Options") || name.endsWith("Mask") || name.endsWith("Usage") || name.endsWith("Stages") ||
                values.size > 2 && values.all { v -> v.value.filter { it.isDigit() }.ifEmpty { "0" }.toBigInteger().let { it.signum() == 0 || it.bitCount() == 1 } }
            if (flags) {
                val wide = prim === LONG_PRIM || prim === INT_PRIM
                fun expression(op: String, invert: Boolean): String {
                    val other = if (invert) "other.value.inv()" else "other.value"
                    return if (wide) "value $op $other" else "(value.toInt() $op ${other.replace("other.value", "other.value.toInt()")}).to${prim.access}()"
                }
                for ((op, operator, expression) in listOf(
                    Triple("or", false, expression("or", false)), Triple("and", false, expression("and", false)),
                    Triple("plus", true, expression("or", false)), Triple("minus", true, expression("and", true)),
                )) {
                    builder.addFunction(
                        FunSpec.builder(op).addModifiers(if (operator) KModifier.OPERATOR else KModifier.INFIX)
                            .addParameter("other", cls).returns(cls).addStatement("return %T(%L)", cls, expression).build()
                    )
                }
                val contains = if (wide) "value and other.value == other.value" else "value.toInt() and other.value.toInt() == other.value.toInt()"
                builder.addFunction(FunSpec.builder("contains").addModifiers(KModifier.OPERATOR).addParameter("other", cls).returns(BOOLEAN).addStatement("return $contains").build())
            }
            val companion = TypeSpec.companionObjectBuilder()
            val used = HashSet<String>()
            for (constant in values) {
                if (!constant.value.first().let { it.isDigit() || it == '-' }) continue
                var member = constant.name.removePrefix(name).trimStart('_')
                if (member.isEmpty()) continue
                if (member.first().isDigit()) member = (Regex("[A-Z][a-z]+").findAll(name).lastOrNull()?.value ?: "Value") + member
                if (!used.add(member)) continue
                companion.addProperty(PropertySpec.builder(member, cls).initializer("%T(%L)", cls, literal(constant.value, prim)).build())
            }
            addType(builder.addType(companion.build()).build())
        }
    }

    private fun literal(text: String, prim: Prim): String {
        if (prim === FLOAT_PRIM) return text.removeSuffix("f").removeSuffix("F") + "f"
        if (prim === DOUBLE_PRIM) return text
        val value = text.substringBefore('.').trimEnd('u', 'U', 'L', 'l').toBigInteger()
        return when (prim) {
            LONG_PRIM -> value.toLong().let { if (it == Long.MIN_VALUE) "Long.MIN_VALUE" else "${it}L" }
            INT_PRIM -> value.toInt().let { if (it == Int.MIN_VALUE) "Int.MIN_VALUE" else "$it" }
            SHORT_PRIM -> "${value.toShort()}"
            BYTE_PRIM -> "${value.toByte()}"
            else -> value.toString()
        }
    }

    private fun read(ref: Ref, at: CodeBlock): CodeBlock = when (ref) {
        is Prim -> CodeBlock.of("%T.get%L(%L)", MEMORY, ref.access, at)
        BoolRef -> CodeBlock.of("%T.getByte(%L) != 0.toByte()", MEMORY, at)
        is EnumRef -> CodeBlock.of("%T(%T.get%L(%L))", metal(ref.name), MEMORY, ref.prim.access, at)
        is StructRef -> CodeBlock.of("%T(%L)", metal(ref.name), at)
        else -> error("No memory form")
    }

    private fun write(ref: Ref, at: CodeBlock, value: String): CodeBlock = when (ref) {
        is Prim -> CodeBlock.of("%T.put%L(%L, %N)", MEMORY, ref.access, at, value)
        BoolRef -> CodeBlock.of("%T.putByte(%L, if (%N) 1 else 0)", MEMORY, at, value)
        is EnumRef -> CodeBlock.of("%T.put%L(%L, %N.value)", MEMORY, ref.prim.access, at, value)
        is StructRef -> CodeBlock.of("%T.copy(%N.address, %L, %T.SIZE)", MEMORY, value, at, metal(ref.name))
        else -> error("No memory form")
    }

    private fun writeStructs() = file("commonMain", "Structs") {
        for ((name, struct) in structs) {
            val cls = metal(name)
            val builder = TypeSpec.classBuilder(name).addModifiers(KModifier.VALUE).addAnnotation(JVM_INLINE)
                .primaryConstructor(FunSpec.constructorBuilder().addParameter("address", LONG).build())
                .addProperty(PropertySpec.builder("address", LONG).initializer("address").build())
            val of = FunSpec.builder("of").contextParameter("stack", STACK).returns(cls).addStatement("val struct = allocate()")
            for (field in struct.fields) {
                val ref = fieldRef(field.type)!!
                val at = if (field.offset == 0L) CodeBlock.of("address") else CodeBlock.of("address + %L", field.offset)
                val type = ref.kotlin()
                val property = PropertySpec.builder(field.name, type).getter(FunSpec.getterBuilder().addStatement("return %L", read(ref, at)).build())
                if (ref !is StructRef) property.mutable(true).setter(FunSpec.setterBuilder().addParameter("value", type).addStatement("%L", write(ref, at, "value")).build())
                builder.addProperty(property.build())
                of.addParameter(field.name, type)
                of.addStatement("%L", write(ref, if (field.offset == 0L) CodeBlock.of("struct.address") else CodeBlock.of("struct.address + %L", field.offset), field.name))
            }
            of.addStatement("return struct")
            builder.addType(
                TypeSpec.companionObjectBuilder()
                    .addProperty(PropertySpec.builder("SIZE", LONG, KModifier.CONST).initializer("%L", struct.size).build())
                    .addProperty(PropertySpec.builder("ALIGNMENT", LONG, KModifier.CONST).initializer("%L", struct.align).build())
                    .addFunction(FunSpec.builder("allocate").contextParameter("stack", STACK).returns(cls).addStatement("return %T(stack.allocate(SIZE, ALIGNMENT))", cls).build())
                    .addFunction(of.build())
                    .build()
            )
            addType(builder.build())
        }
    }

    private fun knClass(path: String, resolveForward: Boolean = false): ClassName {
        val clean = path.removeSuffix("?")
        val real = if (resolveForward && clean.startsWith("objcnames/")) sourceOf[wrapperName(clean)] ?: clean else clean
        val parts = real.split('/')
        return ClassName(parts.dropLast(1).joinToString("."), parts.last().split('.'))
    }

    private fun knType(text: String): TypeName {
        val base = Dump.baseOf(text)
        val nullable = base.endsWith("?")
        val clean = base.removeSuffix("?")
        if (clean == "*") return STAR
        if (clean.startsWith("out ")) return WildcardTypeName.producerOf(knType(clean.removePrefix("out ")))
        val open = clean.indexOf('<')
        val type: TypeName = if (open < 0) knClass(clean) else {
            val raw = knClass(clean.substring(0, open))
            val args = Dump.splitTop(clean.substring(open + 1, clean.length - 1)).map { knType(it.trim()) }
            raw.parameterizedBy(args)
        }
        return type.copy(nullable = nullable)
    }

    private fun pointee(text: String): TypeName {
        val base = Dump.baseOf(text).removeSuffix("?")
        if (!base.startsWith("kotlinx/cinterop/CPointer<")) return C_POINTED
        val inner = base.removePrefix("kotlinx/cinterop/CPointer<").removeSuffix(">").removePrefix("out ")
        return knType(inner).copy(nullable = false).let { if (it is ClassName || it is com.squareup.kotlinpoet.ParameterizedTypeName) it else C_POINTED }
    }

    private fun parameters(member: Member, defaults: Boolean): List<ParameterSpec> = member.params.filter { it.second !== ErrorRef }.map { (param, ref) ->
        ParameterSpec.builder(param.name, ref.kotlin()).apply { if (defaults && ref is StringRef && ref.nullable) defaultValue("null") }.build()
    }

    private fun jvmCall(member: Member, method: DumpMethod, receiver: CodeBlock, params: List<Pair<DumpParam, Ref>>, ret: Ref?, out: FunSpec.Builder, values: List<String>) {
        val tokens = mutableListOf("J", "J") + params.map { it.second.token() }
        val name = signature(tokens, ret?.token() ?: "V")
        val pass = mutableListOf(CodeBlock.of("%T.msgSend", OBJC), receiver, CodeBlock.of("%T.%N", SELECTORS, selector(method.selector)))
        val after = ArrayList<CodeBlock>()
        for ((index, entry) in params.withIndex()) {
            val (_, ref) = entry
            val value = values[index]
            pass += when (ref) {
                ErrorRef -> {
                    out.addStatement("val error = stack.allocate(8)")
                    after += CodeBlock.of("%T.getLong(error).let { if (it != 0L) throw %T(%T.describe(it)) }", MEMORY, EXCEPTION, OBJC)
                    CodeBlock.of("error")
                }
                BoolRef -> CodeBlock.of("if (%N) 1.toByte() else 0.toByte()", value)
                is EnumRef -> CodeBlock.of("%N.value", value)
                is ObjRef, AnyObj -> CodeBlock.of("%N.id", value)
                is StringRef -> if (ref.nullable) CodeBlock.of("if (%N == null) 0L else %T.string(%N)", value, OBJC, value) else CodeBlock.of("%T.string(%N)", OBJC, value)
                is StructRef, is StructPointer -> CodeBlock.of("%N.address", value)
                else -> CodeBlock.of("%N", value)
            }
        }
        val invocation = CodeBlock.of("%N(%L)", name, pass.joinToCode(", "))
        when (ret) {
            null -> {
                out.addStatement("%L", invocation)
                after.forEach { out.addStatement("%L", it) }
            }
            is StructRef -> {
                out.addStatement("val result = %T.allocate()", metal(ret.name))
                out.addStatement("%N(%L)", name, (pass + CodeBlock.of("result.address")).joinToCode(", "))
                after.forEach { out.addStatement("%L", it) }
                out.addStatement("return result")
            }
            else -> {
                out.addStatement("val result = %L", invocation)
                after.forEach { out.addStatement("%L", it) }
                out.addStatement("return %L", jvmWrap(ret, CodeBlock.of("result")))
            }
        }
    }

    private fun jvmWrap(ref: Ref, raw: CodeBlock): CodeBlock = when (ref) {
        BoolRef -> CodeBlock.of("%L != 0.toByte()", raw)
        is EnumRef -> CodeBlock.of("%T(%L)", metal(ref.name), raw)
        is ObjRef -> CodeBlock.of("%T(%L)", metal(ref.name), raw)
        AnyObj -> CodeBlock.of("%T(%L)", NS_OBJECT, raw)
        is StringRef -> if (ref.nullable) CodeBlock.of("%T.text(%L)", OBJC, raw) else CodeBlock.of("%T.text(%L)!!", OBJC, raw)
        is StructPointer -> CodeBlock.of("%T(%L)", metal(ref.name), raw)
        else -> raw
    }

    private fun unsignedSuffix(knText: String): String? = Dump.baseOf(knText).removeSuffix("?").takeIf { it.startsWith("kotlin/U") }?.removePrefix("kotlin/")

    private fun appleArg(param: DumpParam, ref: Ref, value: String): CodeBlock = when (ref) {
        is Prim -> unsignedSuffix(param.type)?.let { CodeBlock.of("%N.to%L()", value, it) } ?: CodeBlock.of("%N", value)
        BoolRef -> CodeBlock.of("%N", value)
        is EnumRef -> if (ref.kotlinEnum) CodeBlock.of("%T.byValue(%N.value%L)", knClass(Dump.baseOf(param.type).removeSuffix("?")), value, ref.unsigned?.let { ".to$it()" } ?: "")
            else unsignedSuffix(param.type)?.let { CodeBlock.of("%N.value.to%L()", value, it) } ?: CodeBlock.of("%N.value", value)
        is ObjRef, AnyObj -> {
            val type = knType(param.type)
            CodeBlock.of(
                "%M<%T>(%N.id.%M<%T>().%M)%L", INTERPRET_OBJC, type.copy(nullable = false), value, TO_C_POINTER, C_POINTED, RAW_VALUE,
                if (type.isNullable) "" else "!!",
            )
        }
        is StringRef -> CodeBlock.of("%N", value)
        is StructRef -> CodeBlock.of("%N.address.%M<%T>()!!.%M.%M()", value, TO_C_POINTER, structClass(ref.name), POINTED, READ_VALUE)
        is StructPointer -> CodeBlock.of("%N.address.%M<%T>()", value, TO_C_POINTER, structClass(ref.name))
        PointerRef -> CodeBlock.of("%N.%M<%T>()", value, TO_C_POINTER, pointee(param.type))
        ErrorRef -> CodeBlock.of("error.%M", PTR)
    }

    private fun appleResult(ref: Ref, knReturn: String, raw: CodeBlock, owned: Boolean): CodeBlock = when (ref) {
        is Prim -> if (unsignedSuffix(knReturn) != null) CodeBlock.of("%L.to%L()", raw, ref.access) else raw
        BoolRef -> raw
        is EnumRef -> CodeBlock.of(
            "%T(%L)", metal(ref.name),
            when {
                ref.kotlinEnum -> CodeBlock.of("%L.value%L", raw, if (ref.unsigned != null) ".to${ref.prim.access}()" else "")
                unsignedSuffix(knReturn) != null -> CodeBlock.of("%L.to%L()", raw, ref.prim.access)
                else -> raw
            },
        )
        is ObjRef, AnyObj -> {
            val id = if (owned) CodeBlock.of("%M(%L).%M()", BRIDGING_RETAIN, raw, TO_LONG) else CodeBlock.of("%M<%T>(%L.%M()).%M()", INTERPRET_C, C_POINTED, raw, OBJC_PTR, TO_LONG)
            CodeBlock.of("%T(%L)", if (ref is ObjRef) metal(ref.name) else NS_OBJECT, id)
        }
        is StringRef -> raw
        is StructPointer -> CodeBlock.of("%T(%L.%M())", metal(ref.name), raw, TO_LONG)
        PointerRef -> CodeBlock.of("%L.%M()", raw, TO_LONG)
        else -> error("No result form")
    }

    private fun structClass(name: String): ClassName = knClass(dump.structs.keys.first { shortName(it) == name })

    private fun appleCall(member: Member, method: DumpMethod, target: CodeBlock, params: List<Pair<DumpParam, Ref>>, ret: Ref?, out: FunSpec.Builder, values: List<String>) {
        val args = params.mapIndexed { i, (param, ref) ->
            val arg = appleArg(param, ref, values[i])
            if (i == 0) arg else CodeBlock.of("%N = %L", param.name, arg)
        }
        val call = CodeBlock.of("%L.%N(%L)", target, method.name, args.joinToCode(", "))
        val error = params.any { it.second === ErrorRef }
        if (error) {
            out.beginControlFlow("return %M", MEM_SCOPED)
            out.addStatement("val error = %M<%T>()", ALLOC, OBJC_OBJECT_VAR.parameterizedBy(NS_ERROR.copy(nullable = true)))
        }
        val returns = if (error) "" else "return "
        when (ret) {
            null -> out.addStatement("%L", call)
            is StructRef -> {
                out.addStatement("val result = %T.allocate()", metal(ret.name))
                out.addStatement("%L.%M { %T.copy(%M.%M(), result.address, %T.SIZE) }", call, MemberName(CINTEROP, "useContents"), MEMORY, PTR, TO_LONG, metal(ret.name))
            }
            else -> out.addStatement("val result = %L", call)
        }
        if (error) out.addStatement("error.%M?.let { throw %T(it.localizedDescription) }", VALUE, EXCEPTION)
        when (ret) {
            null -> if (error) out.addStatement("Unit")
            is StructRef -> out.addStatement("${returns}result")
            else -> out.addStatement("$returns%L", appleResult(ret, method.returns, CodeBlock.of("result"), member.owned))
        }
        if (error) out.endControlFlow()
    }

    private fun receiver(target: Target, name: String, instance: Boolean): CodeBlock = when (target) {
        Target.JVM -> if (instance) CodeBlock.of("id") else CodeBlock.of("%T.%N", CLASSES, shortName(sourceOf.getValue(name)))
        Target.APPLE -> {
            val type = knClass(sourceOf.getValue(name))
            if (instance) CodeBlock.of("%M<%T>(id.%M<%T>().%M)!!", INTERPRET_OBJC, type, TO_C_POINTER, C_POINTED, RAW_VALUE, ) else CodeBlock.of("%T", type)
        }
        else -> CodeBlock.of("")
    }

    private fun render(member: Member, target: Target, owner: String, instance: Boolean): Any {
        val modifier = if (target == Target.EXPECT) emptyList() else listOf(KModifier.ACTUAL)
        val ret = member.ret
        val stub = CodeBlock.of("throw UnsupportedOperationException(%S)", "Metal needs an Apple platform")
        if (member.property) {
            val type = ret!!.kotlin()
            val property = PropertySpec.builder(member.name, type).addModifiers(modifier).mutable(member.setter != null)
            if (target == Target.EXPECT) return property.build()
            val getter = FunSpec.getterBuilder()
            when (target) {
                Target.JVM -> jvmCall(member, member.method, receiver(target, owner, instance), emptyList(), ret, getter, emptyList())
                Target.APPLE -> appleCall(member, member.method, receiver(target, owner, instance), emptyList(), ret, getter, emptyList())
                else -> getter.addStatement("%L", stub)
            }
            property.getter(getter.build())
            member.setter?.let { setter ->
                val set = FunSpec.setterBuilder().addParameter("value", type)
                val params = setter.params.map { it to ref(it.type)!! }
                when (target) {
                    Target.JVM -> jvmCall(member, setter, receiver(target, owner, instance), params, null, set, listOf("value"))
                    Target.APPLE -> appleCall(member, setter, receiver(target, owner, instance), params, null, set, listOf("value"))
                    else -> set.addStatement("%L", stub)
                }
                property.setter(set.build())
            }
            return property.build()
        }
        val function = FunSpec.builder(member.name).addModifiers(modifier).addParameters(parameters(member, target == Target.EXPECT))
        if (member.needsStack) function.contextParameter("stack", STACK)
        if (ret != null) function.returns(ret.kotlin())
        val values = member.params.map { it.first.name }
        when (target) {
            Target.EXPECT -> {}
            Target.JVM -> jvmCall(member, member.method, receiver(target, owner, instance), member.params, ret, function, values)
            Target.APPLE -> appleCall(member, member.method, receiver(target, owner, instance), member.params, ret, function, values)
            Target.STUB -> function.addStatement("%L", stub)
        }
        return function.build()
    }

    private fun writeObjects(members: Map<String, Pair<List<Member>, List<Member>>>) {
        for (target in Target.entries) {
            val sourceSets = when (target) {
                Target.EXPECT -> listOf("commonMain")
                Target.JVM -> listOf("jvmMain")
                Target.APPLE -> listOf("appleMain")
                Target.STUB -> listOf("linuxMain", "mingwMain")
            }
            for (sourceSet in sourceSets) classes.entries.chunked(60).forEachIndexed { chunk, entries ->
                file(sourceSet, "Objects$chunk${if (target == Target.EXPECT) "" else "." + sourceSet.removeSuffix("Main")}") {
                    for ((source, cls) in entries) {
                        val name = wrappers.getValue(source)
                        val (instance, statics) = members.getValue(source)
                        addType(objectType(target, name, cls, instance, statics))
                        if (target == Target.EXPECT) commonExtras(this, source, name, cls)
                    }
                }
            }
        }
    }

    private fun objectType(target: Target, name: String, cls: DumpClass, instance: List<Member>, statics: List<Member>): TypeSpec {
        val type = metal(name)
        val actual = target != Target.EXPECT
        val modifier = if (actual) KModifier.ACTUAL else KModifier.EXPECT
        val builder = TypeSpec.classBuilder(name).addModifiers(modifier).apply { if (actual) addModifiers(KModifier.VALUE) }
            .primaryConstructor(FunSpec.constructorBuilder().addParameter("id", LONG).build())
            .apply { if (actual) addProperty(PropertySpec.builder("id", LONG).addModifiers(KModifier.ACTUAL).initializer("id").build()) }
        if (target == Target.JVM) builder.addAnnotation(JVM_INLINE)
        val retain = FunSpec.builder("retain").returns(type).apply { if (actual) addModifiers(KModifier.ACTUAL) }
        val release = FunSpec.builder("release").apply { if (actual) addModifiers(KModifier.ACTUAL) }
        when (target) {
            Target.JVM -> {
                retain.addStatement("return %T(%T.retain(id))", type, OBJC)
                release.addStatement("%T.release(id)", OBJC)
            }
            Target.APPLE -> {
                retain.addStatement("return %T(%M(id.%M<%T>()).%M())", type, CF_RETAIN, TO_C_POINTER, C_POINTED, TO_LONG)
                release.addStatement("%M(id.%M<%T>())", CF_RELEASE, TO_C_POINTER, C_POINTED)
            }
            Target.STUB -> {
                retain.addStatement("throw UnsupportedOperationException(%S)", "Metal needs an Apple platform")
                release.addStatement("throw UnsupportedOperationException(%S)", "Metal needs an Apple platform")
            }
            Target.EXPECT -> {}
        }
        builder.addFunction(retain.build()).addFunction(release.build())
        for (member in instance) when (val spec = render(member, target, name, instance = true)) {
            is PropertySpec -> builder.addProperty(spec)
            is FunSpec -> builder.addFunction(spec)
        }
        val companion = TypeSpec.companionObjectBuilder().apply { if (actual) addModifiers(KModifier.ACTUAL) }
            .addProperty(PropertySpec.builder("NULL", type).apply { if (actual) addModifiers(KModifier.ACTUAL).initializer("%T(0)", type) }.build())
        if (!cls.protocol && (cls.constructible || statics.isNotEmpty())) {
            classObjects += shortName(sourceOf.getValue(name))
            for (member in statics) companion.addFunction(render(member, target, name, instance = false) as FunSpec)
            if (cls.constructible) {
                val create = FunSpec.builder("new").returns(type).apply { if (actual) addModifiers(KModifier.ACTUAL) }
                when (target) {
                    Target.JVM -> create.addStatement("return %T(%T.create(%T.%N))", type, OBJC, CLASSES, shortName(sourceOf.getValue(name)))
                    Target.APPLE -> create.addStatement("return %T(%M(%T()).%M())", type, BRIDGING_RETAIN, knClass(sourceOf.getValue(name)), TO_LONG)
                    Target.STUB -> create.addStatement("throw UnsupportedOperationException(%S)", "Metal needs an Apple platform")
                    Target.EXPECT -> {}
                }
                companion.addFunction(create.build())
            }
        }
        return builder.addType(companion.build()).build()
    }

    private fun commonExtras(file: FileSpec.Builder, source: String, name: String, cls: DumpClass) {
        val type = metal(name)
        file.addProperty(PropertySpec.builder("isNull", BOOLEAN).receiver(type).getter(FunSpec.getterBuilder().addStatement("return id == 0L").build()).build())
        for (parent in ancestors(source)) {
            val parentName = wrappers.getValue(parent)
            file.addProperty(
                PropertySpec.builder("as$parentName", metal(parentName)).receiver(type)
                    .getter(FunSpec.getterBuilder().addStatement("return %T(id)", metal(parentName)).build()).build()
            )
        }
        if (!cls.protocol && cls.constructible) file.addFunction(
            FunSpec.builder(name).addModifiers(KModifier.INLINE)
                .addParameter("block", LambdaTypeName.get(type, emptyList(), UNIT))
                .returns(type)
                .addStatement("return %T.new().apply(block)", type).build()
        )
    }

    private fun writeFunctions() {
        val functions = dump.functions.filter { (it.name.startsWith("MTL") || it.name.startsWith("CAMetal")) && !it.name.endsWith("Make") }
            .mapNotNull { function ->
                val params = function.params.map { it to (ref(it.type) ?: return@mapNotNull null) }
                val ret = if (Dump.baseOf(function.returns) == "kotlin/Unit") null else ref(function.returns) ?: return@mapNotNull null
                if (ret is StructRef || params.any { it.second === ErrorRef || it.second is StructRef }) return@mapNotNull null
                Triple(function, params, ret)
            }
        for (target in Target.entries) {
            val sourceSets = when (target) {
                Target.EXPECT -> listOf("commonMain")
                Target.JVM -> listOf("jvmMain")
                Target.APPLE -> listOf("appleMain")
                Target.STUB -> listOf("linuxMain", "mingwMain")
            }
            for (sourceSet in sourceSets) file(sourceSet, "Functions${if (target == Target.EXPECT) "" else "." + sourceSet.removeSuffix("Main")}") {
                for ((function, params, ret) in functions) {
                    val spec = FunSpec.builder(function.name).addModifiers(if (target == Target.EXPECT) KModifier.EXPECT else KModifier.ACTUAL)
                    params.forEach { (p, r) -> spec.addParameter(p.name, r.kotlin()) }
                    if (ret != null) spec.returns(ret.kotlin())
                    val owned = "Create" in function.name || "Copy" in function.name
                    when (target) {
                        Target.EXPECT -> {}
                        Target.JVM -> {
                            val name = signature(params.map { it.second.token() }, ret?.token() ?: "V")
                            val pass = listOf(CodeBlock.of("%T.function(%S)", OBJC, function.name)) + params.map { (p, r) ->
                                when (r) {
                                    is ObjRef, AnyObj -> CodeBlock.of("%N.id", p.name)
                                    is EnumRef -> CodeBlock.of("%N.value", p.name)
                                    BoolRef -> CodeBlock.of("if (%N) 1.toByte() else 0.toByte()", p.name)
                                    is StringRef -> if (r.nullable) CodeBlock.of("if (%N == null) 0L else %T.string(%N)", p.name, OBJC, p.name) else CodeBlock.of("%T.string(%N)", OBJC, p.name)
                                    is StructPointer -> CodeBlock.of("%N.address", p.name)
                                    else -> CodeBlock.of("%N", p.name)
                                }
                            }
                            val call = CodeBlock.of("%N(%L)", name, pass.joinToCode(", "))
                            if (ret == null) spec.addStatement("%L", call) else spec.addStatement("return %L", jvmWrap(ret, call))
                        }
                        Target.APPLE -> {
                            val args = params.map { (p, r) -> appleArg(p, r, p.name) }.joinToCode(", ")
                            val call = CodeBlock.of("%M(%L)", MemberName("platform.Metal", function.name), args)
                            if (ret == null) spec.addStatement("%L", call) else spec.addStatement("return %L", appleResult(ret, function.returns, call, owned))
                        }
                        Target.STUB -> spec.addStatement("throw UnsupportedOperationException(%S)", "Metal needs an Apple platform")
                    }
                    addFunction(spec.build())
                }
            }
        }
    }

    private fun writeTables() = file("jvmMain", "Tables") {
        val sel = TypeSpec.objectBuilder("Selectors").addModifiers(KModifier.INTERNAL)
        for ((selector, name) in selectors) sel.addProperty(PropertySpec.builder(name, LONG).initializer("%T.selector(%S)", OBJC, selector).build())
        addType(sel.build())
        val cls = TypeSpec.objectBuilder("Classes").addModifiers(KModifier.INTERNAL)
        for (name in classObjects) cls.addProperty(PropertySpec.builder(name, LONG).initializer("%T.cls(%S)", OBJC, name).build())
        addType(cls.build())
    }

    private fun kotlinOf(token: String): TypeName = when (token) {
        "B" -> BYTE; "S" -> SHORT; "I" -> INT; "J" -> LONG; "F" -> FLOAT; "D" -> DOUBLE; "V" -> UNIT
        else -> LONG
    }

    private fun layoutName(kind: Char) = when (kind) {
        'B' -> "JAVA_BYTE"; 'S' -> "JAVA_SHORT"; 'I' -> "JAVA_INT"; 'J' -> "JAVA_LONG"; 'F' -> "JAVA_FLOAT"; else -> "JAVA_DOUBLE"
    }

    private fun valueLayout(name: String) = MemberName(ClassName("java.lang.foreign", "ValueLayout"), name)

    private fun jvmLayout(name: String): CodeBlock {
        val struct = structs.getValue(name)
        val memoryLayout = ClassName("java.lang.foreign", "MemoryLayout")
        val layouts = ArrayList<CodeBlock>()
        var offset = 0L
        for (field in struct.fields.sortedBy { it.offset }) {
            if (field.offset > offset) layouts += CodeBlock.of("%T.paddingLayout(%L)", memoryLayout, field.offset - offset)
            val (layout, size) = when (val ref = fieldRef(field.type)!!) {
                is StructRef -> jvmLayout(ref.name) to structs.getValue(ref.name).size
                BoolRef -> CodeBlock.of("%M", valueLayout("JAVA_BYTE")) to 1L
                is EnumRef -> CodeBlock.of("%M", valueLayout(layoutName(ref.prim.kind))) to ref.prim.size
                is Prim -> CodeBlock.of("%M", valueLayout(layoutName(ref.kind))) to ref.size
                else -> error("No layout")
            }
            layouts += CodeBlock.of("%L.withName(%S)", layout, field.name)
            offset = field.offset + size
        }
        if (struct.size > offset) layouts += CodeBlock.of("%T.paddingLayout(%L)", memoryLayout, struct.size - offset)
        return CodeBlock.of("%T.structLayout(%L)", memoryLayout, layouts.joinToCode(", "))
    }

    private fun writeInvokers() {
        val segment = ClassName("java.lang.foreign", "MemorySegment")
        val descriptor = ClassName("java.lang.foreign", "FunctionDescriptor")
        val allocator = ClassName("java.lang.foreign", "SegmentAllocator")
        val handle = ClassName("java.lang.invoke", "MethodHandle")
        file("jvmMain", "Invokers") {
            addProperty(
                PropertySpec.builder("linker", ClassName("java.lang.foreign", "Linker"), KModifier.PRIVATE)
                    .initializer("%T.nativeLinker()", ClassName("java.lang.foreign", "Linker")).build()
            )
            for ((name, shape) in signatures) {
                val (params, ret) = shape
                fun layout(token: String) = if (token.startsWith("T")) jvmLayout(token.drop(1)) else CodeBlock.of("%M", valueLayout(layoutName(token[0])))
                val layouts = params.map(::layout)
                val create = if (ret == "V") CodeBlock.of("%T.ofVoid(%L)", descriptor, layouts.joinToCode(", "))
                else CodeBlock.of("%T.of(%L)", descriptor, (listOf(layout(ret)) + layouts).joinToCode(", "))
                addProperty(PropertySpec.builder("handle$name", handle, KModifier.PRIVATE).initializer("linker.downcallHandle(%L)", create).build())
                val args = params.mapIndexed { i, token ->
                    if (token.startsWith("T")) CodeBlock.of("%T.ofAddress(a$i).reinterpret(%T.SIZE)", segment, metal(token.drop(1))) else CodeBlock.of("a$i")
                }
                val function = FunSpec.builder(name).addModifiers(KModifier.INTERNAL).addParameter("function", LONG)
                params.forEachIndexed { i, token -> function.addParameter("a$i", kotlinOf(token)) }
                when {
                    ret.startsWith("T") -> {
                        function.addParameter("out", LONG)
                        function.addStatement("val allocator: %T = %T.prefixAllocator(%T.ofAddress(out).reinterpret(%T.SIZE))", allocator, allocator, segment, metal(ret.drop(1)))
                        function.addStatement("handle$name.invokeExact(%L) as %T", (listOf(CodeBlock.of("allocator"), CodeBlock.of("%T.ofAddress(function)", segment)) + args).joinToCode(", "), segment)
                    }
                    ret == "V" -> function.addStatement("handle$name.invokeExact(%L)", (listOf(CodeBlock.of("%T.ofAddress(function)", segment)) + args).joinToCode(", "))
                    else -> function.returns(kotlinOf(ret)).addStatement("return handle$name.invokeExact(%L) as %T", (listOf(CodeBlock.of("%T.ofAddress(function)", segment)) + args).joinToCode(", "), kotlinOf(ret))
                }
                addFunction(function.build())
            }
        }
    }
}
