plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.stonecutter.gradle.plugin)
    implementation(libs.unimined.gradle.plugin)
    compileOnly(libs.unimined.mapping.library)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.asm)
    implementation(libs.asm.commons)
    implementation(libs.asm.tree)
    implementation(libs.tomlj)
}

gradlePlugin {
    plugins {
        register("settings") {
            id = "tailgate.settings"
            implementationClass = "io.github.etcherfx.tailgate.gradle.TailgateSettingsPlugin"
        }
        register("node") {
            id = "tailgate.node"
            implementationClass = "io.github.etcherfx.tailgate.gradle.TailgateNodePlugin"
        }
        register("merge") {
            id = "tailgate.merge"
            implementationClass = "io.github.etcherfx.tailgate.gradle.TailgateMergePlugin"
        }
    }
}
