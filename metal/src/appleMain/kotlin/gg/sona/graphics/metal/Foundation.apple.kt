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
@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package gg.sona.graphics.metal

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.interpretObjCPointerOrNull
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSArray
import platform.Foundation.NSError
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSURL
import platform.darwin.dispatch_data_create

private fun Long.objc(): platform.darwin.NSObject = interpretObjCPointerOrNull<platform.darwin.NSObject>(toCPointer<CPointed>().rawValue)!!

actual fun <R> autoreleasePool(block: () -> R): R = autoreleasepool { block() }

actual fun NSObject.respondsTo(selector: String): Boolean = id.objc().respondsToSelector(NSSelectorFromString(selector))

actual val NSObject.arrayCount: Long get() = (id.objc() as NSArray).count.toLong()

actual fun NSObject.arrayObject(index: Long): NSObject = NSObject((id.objc() as NSArray).objectAtIndex(index.toULong()).objcPtr().toLong())

actual val NSObject.errorDescription: String get() = (id.objc() as NSError).localizedDescription

actual val NSObject.errorCode: Long get() = (id.objc() as NSError).code

actual fun fileUrl(path: String): NSObject = NSObject(NSURL.fileURLWithPath(path).objcPtr().toLong())

actual fun dispatchData(bytes: ByteArray): NSObject = bytes.copyOf(maxOf(bytes.size, 1)).usePinned { pinned ->
    NSObject(CFBridgingRetain(dispatch_data_create(pinned.addressOf(0), bytes.size.toULong(), null, null)).toLong())
}

actual fun setEnvironment(name: String, value: String, overwrite: Boolean) {
    platform.posix.setenv(name, value, if (overwrite) 1 else 0)
}

actual fun nsArray(ids: LongArray): NSObject = NSObject((ids.map { it.objc() } as NSArray).objcPtr().toLong())
