plugins {
    id("uxmskyblock.java-conventions")
}

dependencies {
    implementation(project(":core"))
    api(project(":api"))

    implementation(libs.uxmlib.storage)

    // Network JDBC drivers for runtime database backends; SQLite is bundled transitively by uxmlib-storage
    runtimeOnly(libs.mariadb.jdbc)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.archunit.junit)
    testImplementation(libs.jqwik)
    testImplementation(libs.testcontainers)
}
