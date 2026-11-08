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

expect fun <R> autoreleasePool(block: () -> R): R

expect fun NSObject.respondsTo(selector: String): Boolean

expect val NSObject.arrayCount: Long

expect fun NSObject.arrayObject(index: Long): NSObject

expect val NSObject.errorDescription: String

expect val NSObject.errorCode: Long

expect fun fileUrl(path: String): NSObject

expect fun dispatchData(bytes: ByteArray): NSObject

expect fun setEnvironment(name: String, value: String, overwrite: Boolean)

expect fun nsArray(ids: LongArray): NSObject
