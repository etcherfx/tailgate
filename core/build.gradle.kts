import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

dependencies {
    compileOnly(libs.kotlin.stdlib)
    testImplementation(libs.kotlin.stdlib)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.bouncycastle.pkix)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
        freeCompilerArgs.add("-Xjdk-release=1.8")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 8
    options.compilerArgs.add("-Xlint:-options")
}

// CI runs the suite on each supported runtime with -Ptailgate.testJava=<8|17|21|25>.
val testJava = providers.gradleProperty("tailgate.testJava").map { it.toInt() }

tasks.test {
    useJUnitPlatform()
    // Resolve localhost to ::1 first, so the TLS tests also cover falling back to the next address.
    systemProperty("java.net.preferIPv6Addresses", "true")
    if (testJava.isPresent) {
        javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(testJava.get()) }
    }
}

tasks.register<JavaExec>("selfTestFixture") {
    description = "Serves the TLS fixture the in-game self-test connects to (runs until killed)."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "io.github.etcherfx.tailgate.core.SelfTestFixture"
    args(layout.buildDirectory.dir("selftest-fixture").get().asFile.absolutePath)
}
