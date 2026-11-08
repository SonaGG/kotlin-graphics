package gg.sona.graphics.runtime

expect class NativeLibrary {
    fun symbol(name: String): Long

    companion object {
        fun open(vararg names: String): NativeLibrary
    }
}
