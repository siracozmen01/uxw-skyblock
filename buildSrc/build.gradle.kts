plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
}

dependencies {
    // The version catalog accessor type used inside precompiled script plugins
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))

    // Plugins applied via id("...") in precompiled scripts
    implementation("com.diffplug.spotless:spotless-plugin-gradle:8.7.0")
    implementation("net.ltgt.gradle:gradle-errorprone-plugin:5.1.0")
    implementation("net.ltgt.gradle:gradle-nullaway-plugin:3.1.0")
}
