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
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion

plugins {
    kotlin("multiplatform")
}

val generated = layout.buildDirectory.dir("generated/metal")
val dumps = layout.buildDirectory.dir("metal-dump")

val generator by configurations.creating {
    isCanBeConsumed = false
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
}

dependencies {
    generator(project(":generator"))
}

val host = System.getProperty("os.name").lowercase().let { os ->
    val arch = if (System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")) "aarch64" else "x86_64"
    when {
        os.contains("win") -> "windows-$arch"
        os.contains("mac") -> "macos-$arch"
        else -> "linux-$arch"
    }
}
val nativeHome = File(System.getProperty("user.home"), ".konan/kotlin-native-prebuilt-$host-${getKotlinPluginVersion()}")
val klibTool = File(nativeHome, if (host.startsWith("windows")) "bin/klib.bat" else "bin/klib")
val frameworks = listOf("Metal", "QuartzCore", "CoreGraphics")

val dumpMetal by tasks.registering {
    dependsOn("commonizeNativeDistribution")
    val libraries = frameworks.map { File(nativeHome, "klib/platform/macos_arm64/org.jetbrains.kotlin.native.platform.$it") }
    inputs.files(libraries)
    outputs.dir(dumps)
    doLast {
        val out = dumps.get().asFile.apply { mkdirs() }
        for ((framework, library) in frameworks.zip(libraries)) {
            val target = File(out, "$framework.txt")
            providers.exec {
                commandLine(klibTool.path, "dump-metadata", library.path)
            }.standardOutput.asText.get().let(target::writeText)
        }
    }
}

val generateMetal by tasks.registering(JavaExec::class) {
    dependsOn(dumpMetal)
    classpath = generator
    mainClass.set("gg.sona.graphics.generator.MainKt")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    inputs.dir(dumps)
    outputs.dir(generated)
    args(listOf("metal", generated.get().asFile.path) + frameworks.map { dumps.get().file("$it.txt").asFile.path })
}

kotlin {
    sourceSets {
        commonMain {
            kotlin.srcDir(generateMetal.map { generated.get().dir("commonMain/kotlin") })
            dependencies {
                api(project(":runtime"))
            }
        }
        jvmMain { kotlin.srcDir(generateMetal.map { generated.get().dir("jvmMain/kotlin") }) }
        appleMain { kotlin.srcDir(generateMetal.map { generated.get().dir("appleMain/kotlin") }) }
        linuxMain { kotlin.srcDir(generateMetal.map { generated.get().dir("linuxMain/kotlin") }) }
        mingwMain { kotlin.srcDir(generateMetal.map { generated.get().dir("mingwMain/kotlin") }) }
        for (set in listOf(linuxMain, mingwMain)) set.get().kotlin.srcDir("src/otherMain/kotlin")
    }
}
