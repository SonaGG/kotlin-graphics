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
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
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
        apply(plugin = "maven-publish")
        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name = "kotlin-graphics ${project.name}"
                    description = "Kotlin Multiplatform bindings for Vulkan and Metal: the ${project.name} module."
                    url = "https://github.com/SonaGG/kotlin-graphics"
                    licenses {
                        license {
                            name = "Apache-2.0"
                            url = "https://www.apache.org/licenses/LICENSE-2.0"
                        }
                    }
                }
            }
            repositories {
                maven {
                    name = "Clover"
                    url = uri(
                        setting("cloverUrl", "CLOVER_MAVEN_URL") ?: if (version.toString().endsWith("SNAPSHOT")) "https://maven.cloverclient.com/snapshots"
                        else "https://maven.cloverclient.com/releases",
                    )
                    credentials {
                        username = setting("cloverUsername", "CLOVER_MAVEN_USERNAME")
                        password = setting("cloverPassword", "CLOVER_MAVEN_PASSWORD")
                    }
                }
            }
        }
    }
}

fun setting(property: String, environment: String): String? =
    (providers.gradleProperty(property).orNull ?: providers.environmentVariable(environment).orNull)
        ?.takeIf { it.isNotBlank() }
