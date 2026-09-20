import net.ltgt.gradle.errorprone.errorprone
import net.ltgt.gradle.nullaway.nullaway

plugins {
    id("java-library")
    id("com.diffplug.spotless")
    id("net.ltgt.errorprone")
    id("net.ltgt.nullaway")
}

val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
        vendor = JvmVendorSpec.ADOPTIUM
    }
    withSourcesJar()
}

dependencies {
    "compileOnly"(libs.jspecify)
    "testCompileOnly"(libs.jspecify)

    "compileOnly"(libs.errorprone.annotations)
    "testCompileOnly"(libs.errorprone.annotations)
    "errorprone"(libs.errorprone.core)
    "errorprone"(libs.nullaway)

    "testImplementation"(platform(libs.junit.bom))
    "testImplementation"(libs.bundles.testing)
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf(
        "-Xlint:all",
        "-Xlint:-processing",
        "-Xlint:-serial",
        "-Xlint:-dangling-doc-comments",
        "-Werror",
        "-parameters"
    ))
    options.errorprone {
        disableWarningsInGeneratedCode.set(true)
        disable("UnnamedVariable")
        disable("RefactorSwitch")
        disable("ReferenceEquality")
        disable("InvalidLink")
        disable("EffectivelyPrivate")
    }
}

extensions.configure<net.ltgt.gradle.nullaway.NullAwayExtension> {
    onlyNullMarked.set(true)
}

tasks.withType<JavaCompile>().configureEach {
    options.errorprone.nullaway {
        severity.set(net.ltgt.gradle.errorprone.CheckSeverity.ERROR)
    }
}

configure<com.diffplug.gradle.spotless.SpotlessExtension> {
    java {
        palantirJavaFormat(libs.versions.palantir.fmt.get())
        removeUnusedImports()
        formatAnnotations()
        importOrder("java", "javax", "org.bukkit", "io.papermc", "net.kyori", "")
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle { ktlint("1.5.0") }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    maxHeapSize = "2g"
}

val verifyNoSkippedTests =
    tasks.register("verifyNoSkippedTests") {
        description = "Fail the build when a test was skipped instead of run."
        group = "verification"
        dependsOn(tasks.named("test"))
        val results = layout.buildDirectory.dir("test-results/test")
        doLast {
            val folder = results.get().asFile
            if (!folder.isDirectory) {
                return@doLast
            }
            val skippedSuites = mutableListOf<String>()
            val counter = Regex("""skipped="(\d+)"""")
            for (file in folder.listFiles().orEmpty()) {
                if (!file.name.startsWith("TEST-") || !file.name.endsWith(".xml")) {
                    continue
                }
                val head = file.readText().substringAfter("<testsuite").substringBefore(">")
                val skipped = counter.find(head)?.groupValues?.get(1)?.toInt() ?: 0
                if (skipped > 0) {
                    skippedSuites.add("  " + file.name.removePrefix("TEST-").removeSuffix(".xml") + ": " + skipped)
                }
            }
            if (skippedSuites.isNotEmpty()) {
                throw GradleException(
                    "A test was skipped rather than run, so it guards nothing:\n" +
                        skippedSuites.joinToString("\n")
                )
            }
        }
    }

tasks.named("check") { dependsOn(verifyNoSkippedTests) }
