dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie" }
        maven("https://maven.wagyourtail.xyz/releases") { name = "WagYourTail" }
        maven("https://maven.wagyourtail.xyz/snapshots") { name = "WagYourTail Snapshots" }
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "build-logic"
