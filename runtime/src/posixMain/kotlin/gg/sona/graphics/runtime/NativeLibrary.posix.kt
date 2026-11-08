package gg.sona.graphics.runtime

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.toLong
import platform.posix.RTLD_LOCAL
import platform.posix.RTLD_NOW
import platform.posix.dlopen
import platform.posix.dlsym

actual class NativeLibrary private constructor(private val handle: COpaquePointer) {
    actual fun symbol(name: String): Long = dlsym(handle, name).toLong()

    actual companion object {
        actual fun open(vararg names: String): NativeLibrary {
            for (name in names) dlopen(name, RTLD_NOW or RTLD_LOCAL)?.let { return NativeLibrary(it) }
            error("Could not load any of ${names.joinToString()}")
        }
    }
}
