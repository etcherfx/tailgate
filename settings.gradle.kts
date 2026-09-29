pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie" }
        maven("https://maven.wagyourtail.xyz/releases") { name = "WagYourTail" }
        maven("https://maven.wagyourtail.xyz/snapshots") { name = "WagYourTail Snapshots" }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("tailgate.settings")
}

rootProject.name = "tailgate"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
