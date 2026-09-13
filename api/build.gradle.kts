plugins {
    id("uxmskyblock.java-conventions")
}

dependencies {
    compileOnly(libs.jspecify)

    testImplementation(libs.archunit.junit)
}
