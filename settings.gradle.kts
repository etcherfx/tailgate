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
    id("tailgate.settings")
}

rootProject.name = "tailgate"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
