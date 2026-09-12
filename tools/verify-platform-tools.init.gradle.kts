// Run after generating verification-metadata.xml with the normal Android build.
gradle.rootProject {
    val metadata = file("gradle/verification-metadata.xml").readText()
    val versions = Regex("""<component group="com.android.tools.build" name="aapt2" version="([^"]+)">""")
        .findAll(metadata).map { it.groupValues[1] }.distinct().toList()
    require(versions.size == 1) { "Regenerate metadata with exactly one resolved AAPT2 version first" }
    val platformTools = listOf("linux", "osx", "windows").map { platform ->
        configurations.detachedConfiguration(dependencies.create("com.android.tools.build:aapt2:${versions.single()}:$platform"))
    }
    tasks.register("verifyPlatformTools") {
        doLast {
            platformTools.forEach { it.resolve() }
        }
    }
}
