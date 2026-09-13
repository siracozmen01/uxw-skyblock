plugins {
    id("uxmskyblock.java-conventions")
}

dependencies {
    api(project(":api"))
    compileOnly(libs.jspecify)

    testImplementation(libs.jqwik)
    testImplementation(libs.archunit.junit)
}
