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
val nativeHome = File(System.getProperty("user.home"), ".konan/kotlin-native-prebuilt-$host-${KotlinVersion.CURRENT}")
val klibTool = File(nativeHome, if (host.startsWith("windows")) "bin/klib.bat" else "bin/klib")
val frameworks = listOf("Metal", "QuartzCore", "CoreGraphics")

val dumpMetal by tasks.registering {
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
    }
}
