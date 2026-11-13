plugins {
    java
    id("org.springframework.boot") version "4.1.2"
    id("com.diffplug.spotless") version "8.1.0"
}

group = "com.validexia"
version = "0.1.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

// Static analysis pins (M02-1 v2.6 §1). Error Prone is wired manually (javac
// plugin) because the ltgt Gradle plugin has no release verified against this
// matrix; the wiring below is the documented manual setup, warnings stage only:
// -XepAllErrorsAsWarnings means no check can fail the build in this mission.
val errorProneVersion = "2.50.0"
val nullAwayVersion = "0.14.3"

configurations {
    create("errorprone")
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.2.0")
    implementation("com.nimbusds:nimbus-jose-jwt:10.0.2")

    // PDF text extraction for the documents slice (migration V13): one text record per page, so a
    // citation can be pinned to the page it came from. Apache License 2.0.
    implementation("org.apache.pdfbox:pdfbox:3.0.8")

    "errorprone"("com.google.errorprone:error_prone_core:$errorProneVersion")
    "errorprone"("com.uber.nullaway:nullaway:$nullAwayVersion")

    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("com.tngtech.archunit:archunit:1.5.1")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    if (name == "compileJava") {
        options.annotationProcessorPath = configurations.getByName("errorprone")
        options.compilerArgs.addAll(
            listOf(
                // Error Prone rejects Gradle's default INIT policy (CI catch, 2026-11-10).
                "--should-stop=ifError=FLOW",
                "-XDcompilePolicy=simple",
                "-XDaddTypeAnnotationsToSymbol=true",
                "-Xplugin:ErrorProne -XepAllErrorsAsWarnings" +
                    " -Xep:NullAway:WARN" +
                    " -XepOpt:NullAway:AnnotatedPackages=com.validexia" +
                    " -XepOpt:NullAway:HandleTestAssertionLibraries=true",
            )
        )
        options.isFork = true
        options.forkOptions.jvmArgs!!.addAll(
            listOf(
                "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
                "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
                "--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
                "--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
                "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
                "--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
                "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
                "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
            )
        )
    }
}

spotless {
    java {
        googleJavaFormat("1.28.0")
        targetExclude("build/**")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

// Release-hygiene gate (spec §1/§9): refuse pre-release dependency versions.
val verifyDependencyPins by tasks.registering {
    group = "verification"
    description = "Fails if any resolved runtime/test dependency is a SNAPSHOT, milestone, RC, alpha or beta."
    doLast {
        val bad = mutableListOf<String>()
        val pattern = Regex("(?i)(snapshot|-m\\d|-rc\\d|\\.m\\d|alpha|beta)")
        listOf("runtimeClasspath", "testRuntimeClasspath").forEach { cfgName ->
            configurations.getByName(cfgName).resolvedConfiguration.resolvedArtifacts.forEach { art ->
                val v = art.moduleVersion.id
                if (pattern.containsMatchIn(v.version)) {
                    bad += "${v.group}:${v.name}:${v.version} ($cfgName)"
                }
            }
        }
        if (bad.isNotEmpty()) {
            throw GradleException("Pre-release dependencies are forbidden:\n" + bad.joinToString("\n"))
        }
    }
}

tasks.named("check") {
    dependsOn(verifyDependencyPins)
}
