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
plugins {
    kotlin("multiplatform")
}

val generated = layout.buildDirectory.dir("generated/vulkan")
val registry = rootProject.layout.projectDirectory.file("registry/vk.xml")

val generator by configurations.creating {
    isCanBeConsumed = false
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
}

dependencies {
    generator(project(":generator"))
}

val generateVulkan by tasks.registering(JavaExec::class) {
    description = "Generates the Vulkan bindings from the registry."
    classpath = generator
    mainClass.set("gg.sona.graphics.generator.MainKt")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    inputs.file(registry)
    outputs.dir(generated)
    args("vulkan", registry.asFile.path, generated.get().asFile.path)
}

kotlin {
    sourceSets {
        commonMain {
            kotlin.srcDir(generateVulkan.map { generated.get().dir("commonMain/kotlin") })
            dependencies {
                api(project(":runtime"))
            }
        }
        jvmMain {
            kotlin.srcDir(generateVulkan.map { generated.get().dir("jvmMain/kotlin") })
        }
        nativeMain {
            kotlin.srcDir(generateVulkan.map { generated.get().dir("nativeMain/kotlin") })
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
