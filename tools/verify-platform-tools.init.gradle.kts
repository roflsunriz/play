// Run after generating verification-metadata.xml with the normal Android build.
gradle.rootProject {
    val metadata = file("gradle/verification-metadata.xml").readText()
    val catalog = file("gradle/libs.versions.toml").readText()
    val agpVersion = Regex("""(?m)^agp\s*=\s*"([^"]+)"\s*$""")
        .find(catalog)?.groupValues?.get(1) ?: error("AGP version is missing from the version catalog")
    val versions = Regex("""<component group="com.android.tools.build" name="aapt2" version="([^"]+)">""")
        .findAll(metadata).map { it.groupValues[1] }.distinct()
        .filter { it.startsWith("$agpVersion-") }.toList()
    require(versions.size == 1) { "Expected one AAPT2 version for current AGP $agpVersion" }
    val platformTools = listOf("linux", "osx", "windows").map { platform ->
        configurations.detachedConfiguration(dependencies.create("com.android.tools.build:aapt2:${versions.single()}:$platform"))
    }
    tasks.register("verifyPlatformTools") {
        doLast {
            platformTools.forEach { it.resolve() }
        }
    }
}
