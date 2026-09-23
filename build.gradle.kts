buildscript {
    dependencies {
        classpath("org.apache.commons:commons-lang3:3.20.0") {
            because("CVE-2025-48924 is fixed in 3.18.0")
        }
        classpath("org.bitbucket.b_c:jose4j:0.9.7") {
            because("CVE-2024-29371 is fixed in 0.9.6")
        }
        classpath("org.bouncycastle:bcpkix-jdk18on:1.86") {
            because("Use the first release containing the current Bouncy Castle security fixes")
        }
        classpath("org.bouncycastle:bcprov-jdk18on:1.86") {
            because("Use the first release containing the current Bouncy Castle security fixes")
        }
        classpath("org.jdom:jdom2:2.0.6.1") {
            because("CVE-2021-33813 is fixed in 2.0.6.1")
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
}
