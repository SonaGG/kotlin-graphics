package gg.sona.graphics.runtime

import java.lang.foreign.Arena
import java.lang.foreign.SymbolLookup

actual class NativeLibrary private constructor(private val lookup: SymbolLookup) {
    actual fun symbol(name: String): Long = lookup.find(name).map { it.address() }.orElse(0L)

    actual companion object {
        actual fun open(vararg names: String): NativeLibrary {
            for (name in names) {
                try {
                    return NativeLibrary(SymbolLookup.libraryLookup(name, Arena.global()))
                } catch (_: IllegalArgumentException) {
                }
            }
            throw UnsatisfiedLinkError("Could not load any of ${names.joinToString()}")
        }
    }
}
