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
