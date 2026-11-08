package gg.sona.graphics.metal

import gg.sona.graphics.runtime.Memory
import gg.sona.graphics.runtime.NativeLibrary
import gg.sona.graphics.runtime.memoryStack
import gg.sona.graphics.runtime.readUtf8
import gg.sona.graphics.runtime.stack

internal object ObjC {
    private val runtime = NativeLibrary.open("/usr/lib/libobjc.A.dylib", "libobjc.dylib")
    private val metal = NativeLibrary.open("/System/Library/Frameworks/Metal.framework/Metal")
    private val quartz = NativeLibrary.open("/System/Library/Frameworks/QuartzCore.framework/QuartzCore")
    private val getClass = runtime.symbol("objc_getClass")
    private val registerName = runtime.symbol("sel_registerName")
    private val functions = HashMap<String, Long>()

    val msgSend = runtime.symbol("objc_msgSend")
    private val alloc = selector("alloc")
    private val init = selector("init")
    private val retainSelector = selector("retain")
    private val releaseSelector = selector("release")
    private val utf8 = selector("UTF8String")
    private val withUtf8 = selector("stringWithUTF8String:")
    private val localizedDescription = selector("localizedDescription")
    private val stringClass = cls("NSString")

    fun selector(name: String): Long = memoryStack { callJ_J(registerName, stack.utf8(name)) }

    fun cls(name: String): Long = memoryStack { callJ_J(getClass, stack.utf8(name)) }

    fun create(cls: Long): Long = callJJ_J(msgSend, callJJ_J(msgSend, cls, alloc), init)

    fun retain(id: Long): Long = callJJ_J(msgSend, id, retainSelector)

    fun release(id: Long) = callJJ_V(msgSend, id, releaseSelector)

    fun string(text: String): Long = memoryStack { callJJJ_J(msgSend, stringClass, withUtf8, stack.utf8(text)) }

    fun text(id: Long): String? = if (id == 0L) null else callJJ_J(msgSend, id, utf8).let { if (it == 0L) null else Memory.readUtf8(it) }

    fun describe(error: Long): String = text(callJJ_J(msgSend, error, localizedDescription)) ?: "Unknown Metal error"

    fun function(name: String): Long = functions.getOrPut(name) {
        metal.symbol(name).takeIf { it != 0L } ?: quartz.symbol(name).takeIf { it != 0L } ?: throw UnsupportedOperationException("$name is not available")
    }
}
