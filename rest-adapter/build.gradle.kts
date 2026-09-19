plugins {
    id("uxmskyblock.java-conventions")
}

dependencies {
    implementation(project(":api"))
    implementation(project(":core"))

    implementation(libs.javalin)
    implementation(libs.gson)
    implementation(libs.slf4j.api)

    testImplementation(libs.bundles.testing)
    testImplementation(libs.slf4j.simple)
}
