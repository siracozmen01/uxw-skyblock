plugins {
    id("uxmskyblock.java-conventions")
}

dependencies {
    implementation(project(":core"))
    api(project(":api"))

    implementation(libs.uxmlib.storage)
    implementation(libs.gson)

    // Network JDBC drivers for runtime database backends; SQLite is bundled transitively by uxmlib-storage
    runtimeOnly(libs.mariadb.jdbc)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.archunit.junit)
    testImplementation(libs.jqwik)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
}

tasks.test {
    useJUnitPlatform {
        excludeTags("database-integration")
    }
}

val databaseIntegrationTest by tasks.registering(Test::class) {
    description = "Runs containerized database integration tests against MariaDB and PostgreSQL"
    group = "verification"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("database-integration")
    }
    systemProperty("skyblock.test.database", System.getProperty("skyblock.test.database", "all"))
    shouldRunAfter(tasks.test)
}

val mariadbIntegrationTest by tasks.registering(Test::class) {
    description = "Runs containerized database integration tests against MariaDB only"
    group = "verification"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("database-integration")
    }
    systemProperty("skyblock.test.database", "mariadb")
    shouldRunAfter(tasks.test)
}

val postgresIntegrationTest by tasks.registering(Test::class) {
    description = "Runs containerized database integration tests against PostgreSQL only"
    group = "verification"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("database-integration")
    }
    systemProperty("skyblock.test.database", "postgresql")
    shouldRunAfter(tasks.test)
}
