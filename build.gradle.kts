allprojects {
    group = "com.uxplima"
    version = project.findProperty("projectVersion")?.toString() ?: "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.codemc.org/repository/maven-public/")
        maven("https://jitpack.io")
        maven("https://repo.extendedclip.com/releases/")
        maven("https://repo.opencollab.dev/main/")
        // Normal reproducible builds must NOT depend on ambient Maven Local state.
        // Gated behind explicit -Puxm.mavenLocal=true flag for local development only.
        if (providers.gradleProperty("uxm.mavenLocal").map { it == "true" }.getOrElse(false)) {
            mavenLocal()
        }
    }
}

tasks.register("printVersion") {
    val current = version.toString()
    doLast { println(current) }
}
