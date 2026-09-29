// Compile-only copies of the loader APIs the entry points touch. Never shipped: each loader
// provides the real classes at runtime.
plugins {
    java
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 8
    options.compilerArgs.add("-Xlint:-options")
}
