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
