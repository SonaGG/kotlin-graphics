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
