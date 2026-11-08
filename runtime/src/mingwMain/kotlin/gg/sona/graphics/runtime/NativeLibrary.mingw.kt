package gg.sona.graphics.runtime

import kotlinx.cinterop.toLong
import platform.windows.GetProcAddress
import platform.windows.HMODULE
import platform.windows.LoadLibraryW

actual class NativeLibrary private constructor(private val module: HMODULE) {
    actual fun symbol(name: String): Long = GetProcAddress(module, name).toLong()

    actual companion object {
        actual fun open(vararg names: String): NativeLibrary {
            for (name in names) LoadLibraryW(name)?.let { return NativeLibrary(it) }
            error("Could not load any of ${names.joinToString()}")
        }
    }
}
