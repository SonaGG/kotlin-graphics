plugins {
    kotlin("multiplatform")
}

kotlin {
    compilerOptions.optIn.add("kotlin.concurrent.atomics.ExperimentalAtomicApi")
    sourceSets {
        commonMain.dependencies {
            api(project(":vulkan"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
