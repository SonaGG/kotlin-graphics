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
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    kotlin("multiplatform") version "2.4.20" apply false
    kotlin("jvm") version "2.4.20" apply false
}

subprojects {
    group = rootProject.property("project.group") as String
    version = rootProject.property("project.version") as String

    repositories {
        mavenCentral()
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<KotlinMultiplatformExtension> {
            jvmToolchain(25)
            jvm()
            mingwX64()
            linuxX64()
            linuxArm64()
            macosArm64()
            macosX64()

            applyDefaultHierarchyTemplate {
                common {
                    group("posix") {
                        withLinux()
                        withApple()
                    }
                }
            }

            compilerOptions {
                freeCompilerArgs.add("-Xexpect-actual-classes")
                optIn.add("kotlinx.cinterop.ExperimentalForeignApi")
            }
        }
        tasks.withType<Test>().configureEach {
            jvmArgs("--enable-native-access=ALL-UNNAMED")
        }
    }
}
