plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(25)
    compilerOptions.optIn.add("com.squareup.kotlinpoet.ExperimentalKotlinPoetApi")
}

dependencies {
    implementation("com.squareup:kotlinpoet:2.2.0")
}

application {
    mainClass.set("gg.sona.graphics.generator.MainKt")
}
