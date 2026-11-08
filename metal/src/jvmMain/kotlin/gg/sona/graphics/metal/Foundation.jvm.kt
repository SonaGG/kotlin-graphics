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
package gg.sona.graphics.metal

import gg.sona.graphics.runtime.Memory
import gg.sona.graphics.runtime.NativeLibrary
import gg.sona.graphics.runtime.memoryStack
import gg.sona.graphics.runtime.stack

private val system by lazy { NativeLibrary.open("/usr/lib/libSystem.B.dylib") }
private val respondsToSelector by lazy { ObjC.selector("respondsToSelector:") }
private val count by lazy { ObjC.selector("count") }
private val objectAtIndex by lazy { ObjC.selector("objectAtIndex:") }
private val code by lazy { ObjC.selector("code") }
private val fileUrlWithPath by lazy { ObjC.selector("fileURLWithPath:") }
private val setLayer by lazy { ObjC.selector("setLayer:") }
private val setWantsLayer by lazy { ObjC.selector("setWantsLayer:") }
private val window by lazy { ObjC.selector("window") }
private val backingScaleFactor by lazy { ObjC.selector("backingScaleFactor") }
private val setContentsScale by lazy { ObjC.selector("setContentsScale:") }
private val setOpaque by lazy { ObjC.selector("setOpaque:") }
private val bounds by lazy { ObjC.selector("bounds") }
private val contentsScale by lazy { ObjC.selector("contentsScale") }

actual fun <R> autoreleasePool(block: () -> R): R {
    val pool = call_J(ObjC.autoreleasePush)
    try {
        return block()
    } finally {
        callJ_V(ObjC.autoreleasePop, pool)
    }
}

actual fun NSObject.respondsTo(selector: String): Boolean = callJJJ_B(ObjC.msgSend, id, respondsToSelector, ObjC.selector(selector)) != 0.toByte()

actual val NSObject.arrayCount: Long get() = callJJ_J(ObjC.msgSend, id, count)

actual fun NSObject.arrayObject(index: Long): NSObject = NSObject(callJJJ_J(ObjC.msgSend, id, objectAtIndex, index))

actual val NSObject.errorDescription: String get() = ObjC.describe(id)

actual val NSObject.errorCode: Long get() = callJJ_J(ObjC.msgSend, id, code)

actual fun fileUrl(path: String): NSObject = NSObject(callJJJ_J(ObjC.msgSend, ObjC.cls("NSURL"), fileUrlWithPath, ObjC.string(path)))

actual fun dispatchData(bytes: ByteArray): NSObject {
    val address = Memory.allocate(maxOf(bytes.size, 1).toLong())
    try {
        Memory.putBytes(address, bytes)
        return NSObject(callJJJJ_J(system.symbol("dispatch_data_create"), address, bytes.size.toLong(), 0L, 0L))
    } finally {
        Memory.free(address)
    }
}

actual fun setEnvironment(name: String, value: String, overwrite: Boolean) {
    memoryStack { callJJI_I(system.symbol("setenv"), stack.utf8(name), stack.utf8(value), if (overwrite) 1 else 0) }
}

actual fun nsArray(ids: LongArray): NSObject = memoryStack {
    val objects = stack.allocate(maxOf(ids.size, 1) * 8L)
    ids.forEachIndexed { index, id -> Memory.putLong(objects + index * 8L, id) }
    NSObject(callJJJJ_J(ObjC.msgSend, ObjC.cls("NSArray"), ObjC.selector("arrayWithObjects:count:"), objects, ids.size.toLong()))
}

fun attachLayerToView(view: Long, layer: CAMetalLayer) {
    callJJJ_V(ObjC.msgSend, view, setLayer, layer.id)
    callJJB_V(ObjC.msgSend, view, setWantsLayer, 1)
    val host = callJJ_J(ObjC.msgSend, view, window)
    callJJD_V(ObjC.msgSend, layer.id, setContentsScale, if (host != 0L) callJJ_D(ObjC.msgSend, host, backingScaleFactor) else 1.0)
}

fun detachLayerFromView(view: Long) = callJJJ_V(ObjC.msgSend, view, setLayer, 0L)

fun layerSetOpaque(layer: CAMetalLayer) = callJJB_V(ObjC.msgSend, layer.id, setOpaque, 1)

fun layerPixelSize(layer: CAMetalLayer): Pair<Int, Int> = memoryStack {
    val rect = CGRect.allocate()
    callJJ__TCGRect_(ObjC.msgSend, layer.id, bounds, rect.address)
    val scale = callJJ_D(ObjC.msgSend, layer.id, contentsScale)
    Math.round(rect.width * scale).toInt() to Math.round(rect.height * scale).toInt()
}

fun releaseObject(value: NSObject) = ObjC.release(value.id)

fun setCaptureObject(descriptor: MTLCaptureDescriptor, target: Long) = callJJJ_V(ObjC.msgSend, descriptor.id, ObjC.selector("setCaptureObject:"), target)

fun dataLongs(data: NSObject): LongArray {
    val bytes = callJJ_J(ObjC.msgSend, data.id, ObjC.selector("bytes"))
    val size = callJJ_J(ObjC.msgSend, data.id, ObjC.selector("length"))
    return LongArray((size / 8).toInt()) { Memory.getLong(bytes + it * 8L) }
}
