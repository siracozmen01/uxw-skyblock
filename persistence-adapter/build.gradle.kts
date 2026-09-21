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

/**
 * A containerized lane that ran nothing is not a pass.
 *
 * Testcontainers reports an unreachable Docker by disabling every test it owns, and a disabled test
 * is recorded as a skip. The build then reports success over a lane that never started a container,
 * which is exactly what happened on a machine whose Docker was newer than the client could speak to.
 * The module wide zero skip guard cannot see this: it reads `test-results/test` and these lanes
 * write somewhere else.
 *
 * A skip is still legitimate here, because each dialect lane disables the other dialect's tests on
 * purpose. What is never legitimate is a lane where every single test was skipped.
 */
fun registerLaneRanGuard(lane: TaskProvider<Test>) {
    val guard = tasks.register("verify${lane.name.replaceFirstChar { it.uppercase() }}Ran") {
        group = "verification"
        description = "Fails when ${lane.name} skipped every test, which means no container ran."
        val results = layout.buildDirectory.dir("test-results/${lane.name}")
        doLast {
            val folder = results.get().asFile
            if (!folder.isDirectory) {
                return@doLast
            }
            val totals = Regex("""tests="(\d+)"""")
            val skips = Regex("""skipped="(\d+)"""")
            var total = 0
            var skipped = 0
            for (file in folder.listFiles().orEmpty()) {
                if (!file.name.startsWith("TEST-") || !file.name.endsWith(".xml")) {
                    continue
                }
                val head = file.readText().substringAfter("<testsuite").substringBefore(">")
                total += totals.find(head)?.groupValues?.get(1)?.toInt() ?: 0
                skipped += skips.find(head)?.groupValues?.get(1)?.toInt() ?: 0
            }
            if (total > 0 && total == skipped) {
                throw GradleException(
                    "${lane.name} skipped all $total of its tests, so no database container ran and " +
                        "the lane proved nothing. Check that Docker is reachable and that the " +
                        "Testcontainers client can speak this daemon's API version."
                )
            }
        }
    }
    lane.configure { finalizedBy(guard) }
}

registerLaneRanGuard(databaseIntegrationTest)
registerLaneRanGuard(mariadbIntegrationTest)
registerLaneRanGuard(postgresIntegrationTest)
