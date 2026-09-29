plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "26.3-fabric"

stonecutter parameters {
    val loader = current.project.substringAfter('-')
    constants.match(loader, "fabric", "forge", "neoforge")
}
