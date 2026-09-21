import java.util.zip.ZipFile

plugins {
    id("uxmskyblock.java-conventions")
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":rest-adapter"))
    implementation(project(":persistence-adapter"))
    api(project(":api"))

    compileOnly(libs.paper.api)
    compileOnly(libs.bundles.adventure)
    testImplementation(libs.paper.api)

    // uxmLib modules (Platform UI, commands, HUD, bedrock, integrations)
    implementation(libs.uxmlib.common)
    implementation(libs.uxmlib.item)
    implementation(libs.uxmlib.gui)
    implementation(libs.uxmlib.menu)
    implementation(libs.uxmlib.command)
    implementation(libs.uxmlib.hud)
    implementation(libs.uxmlib.bedrock)
    implementation(libs.uxmlib.condition)
    implementation(libs.uxmlib.integration)
    implementation(libs.uxmlib.redis)
    implementation(libs.lettuce.core)

    // Testing harness
    testImplementation(libs.mockbukkit)
    testImplementation(libs.archunit.junit)
    testImplementation(libs.jqwik)
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching(listOf("plugin.yml", "paper-plugin.yml")) {
        expand(props)
    }
}

tasks.shadowJar {
    // Paper owns these. A second copy of slf4j with no binding behind it does not fail: it makes
    // this plugin's logging go quiet, which is worse. Adventure is the server's too.
    exclude("org/slf4j/**")
    exclude("net/kyori/**")
    mergeServiceFiles()
}

/**
 * The jar is audited rather than trusted.
 *
 * The REST module brought an HTTP server into the plugin and with it a second slf4j, and nothing in
 * the build said so. A hand count is right on the day somebody runs it and never again.
 */
val verifyJar by tasks.registering {
    description = "Fails when the shaded jar holds a package the server already owns."
    group = "verification"
    dependsOn(tasks.shadowJar)
    val jar = tasks.shadowJar.flatMap { it.archiveFile }
    doLast {
        val forbidden = listOf("org/slf4j/", "net/kyori/")
        val found = mutableListOf<String>()
        ZipFile(jar.get().asFile).use { zip ->
            for (entry in zip.entries()) {
                val name = entry.name
                for (prefix in forbidden) {
                    if (name.startsWith(prefix) && name.endsWith(".class")) {
                        found.add(prefix)
                    }
                }
            }
        }
        if (found.isNotEmpty()) {
            throw GradleException(
                "The shaded jar holds packages the server owns: " +
                    found.distinct().joinToString(", ") +
                    ". A second copy of these does not fail loudly, it makes logging or text go wrong.",
            )
        }
    }
}

tasks.named("check") { dependsOn(verifyJar) }
