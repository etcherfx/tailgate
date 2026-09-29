package io.github.etcherfx.tailgate.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.bundling.Jar

/**
 * Root-project tasks: `mergeJar` builds `tailgate-<version>.jar` from core, entry and every
 * collected node jar; `verifyJar` checks it. Both run as part of `build`. `ciMatrix` prints the
 * CI job matrices.
 *
 * With `-Ptailgate.prebuiltNodes=true` the merge uses node jars already in `build/nodes`
 * (CI builds nodes in parallel jobs and merges them in one final job).
 */
class TailgateMergePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("base")
        val version = project.property("mod.version").toString()
        val nodeSpec = project.providers.gradleProperty("tailgate.nodes").orNull
        val prebuilt = project.providers.gradleProperty("tailgate.prebuiltNodes").orNull == "true"
        val nodesDir = project.layout.buildDirectory.dir("nodes")

        val libs = project.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
        val kotlinStdlib = project.configurations.create("tailgateKotlinStdlib") {
            isTransitive = false
            isCanBeConsumed = false
        }
        project.dependencies.add(kotlinStdlib.name, libs.findLibrary("kotlin-stdlib").get())

        val merge = project.tasks.register("mergeJar", MergeJar::class.java) {
            group = "build"
            description = "Merges core, entry and every UI node into tailgate-$version.jar."
            baseJars.from(project.project(":core").tasks.named("jar", Jar::class.java).flatMap { it.archiveFile })
            baseJars.from(project.project(":entry").tasks.named("jar", Jar::class.java).flatMap { it.archiveFile })
            this.kotlinStdlib.from(kotlinStdlib)
            nodeJars.set(nodesDir)
            targetsFile.set(project.layout.projectDirectory.file("targets.toml"))
            licenseFile.set(project.layout.projectDirectory.file("LICENSE"))
            modVersion.set(version)
            outputJar.set(project.layout.buildDirectory.file("libs/tailgate-$version.jar"))
            doFirst { nodesDir.get().asFile.mkdirs() }
            if (!prebuilt) {
                val nodes = project.rootProject.subprojects.filter { it.parent?.path == ":ui" }
                dependsOn(nodes.map { "${it.path}:collectNode" })
            }
        }
        val verify = project.tasks.register("verifyJar", VerifyJar::class.java) {
            group = "verification"
            description = "Checks the merged jar's bytecode level, relocation, annotations and target coverage."
            jar.set(merge.flatMap { it.outputJar })
            targetsFile.set(project.layout.projectDirectory.file("targets.toml"))
            requireAllTargets.set(prebuilt || nodeSpec == "all")
            report.set(project.layout.buildDirectory.file("reports/verifyJar.txt"))
        }
        project.tasks.register("ciMatrix", CiMatrix::class.java) {
            group = "verification"
            description = "Prints the CI build and runtime-test matrices as GitHub step outputs."
            targetsFile.set(project.layout.projectDirectory.file("targets.toml"))
        }
        project.tasks.named("assemble") { dependsOn(merge) }
        project.tasks.named("check") { dependsOn(verify) }
        nodesDir.get().asFile.mkdirs()
    }
}
