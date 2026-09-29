import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":core"))
    compileOnly(project(":stubs"))
    compileOnly(libs.kotlin.stdlib)
    compileOnly(libs.log4j.api.legacy)
}

val generateVersion = tasks.register("generateVersion") {
    val version = project.property("mod.version").toString()
    val output = layout.buildDirectory.dir("generated/version")
    inputs.property("version", version)
    outputs.dir(output)
    doLast {
        val file = output.get().file("io/github/etcherfx/tailgate/entry/TailgateVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package io.github.etcherfx.tailgate.entry
            |
            |object TailgateVersion {
            |    const val VERSION = "$version"
            |}
            |""".trimMargin(),
        )
    }
}

kotlin {
    sourceSets.main {
        kotlin.srcDir(generateVersion)
    }
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
        freeCompilerArgs.add("-Xjdk-release=1.8")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 8
    options.compilerArgs.add("-Xlint:-options")
}
