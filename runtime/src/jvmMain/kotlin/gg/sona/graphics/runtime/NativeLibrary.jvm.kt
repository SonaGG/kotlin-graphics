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
