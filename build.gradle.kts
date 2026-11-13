import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    jacoco
    alias(libs.plugins.spring.boot)
}

group = "com.valxel"
description = "Valxel event worker template: applies Kafka events to the product's data stores"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("org.flywaydb:flyway-database-postgresql")
    // Feature flags from the product's Flipt over OFREP (com.valxel.worker.flags).
    implementation(libs.openfeature.sdk)
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    // AWS read models (VX-19): DynamoDB through the SDK's default credentials (the pod's identity),
    // on the JDK's HTTP client so no second HTTP stack comes along.
    implementation(platform(libs.aws.sdk.bom))
    implementation("software.amazon.awssdk:dynamodb") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
    }
    implementation("software.amazon.awssdk:url-connection-client")
    // Each AWS call as a span of this component, in OpenChoreo's traces.
    implementation(libs.otel.aws.sdk)
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("org.awaitility:awaitility")
    testImplementation("org.testcontainers:testcontainers")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // kafka-clients brings lz4-java; versions before 1.11.4 have a high advisory.
    constraints {
        implementation("at.yawk.lz4:lz4-java") {
            version { require("[1.11.4,2.0)") }
            because("CVE-2026-106451 (GHSA-mcr4-qmvw-px4g), through kafka-clients")
        }
        implementation("org.xerial.snappy:snappy-java") {
            version { require("[1.1.10.9,2.0)") }
            because("GHSA-q233-g92q-qxp6, GHSA-gwq9-9ffj-mvrv, through kafka-clients")
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial", "-parameters"))
}

// Unit and integration tests together: the integration tests start Postgres and Kafka in
// containers (Testcontainers), so they need Docker, which CI runners have.
tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
    // The performance smoke test runs only when perf.yml passes -Dperf.out=<file>.
    System.getProperty("perf.out")?.let { systemProperty("perf.out", it) }
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

// The platform's floor; products may raise it (<product>-governance/product.yaml quality.unit.min_coverage).
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    violationRules {
        rule { limit { counter = "LINE"; minimum = "0.85".toBigDecimal() } }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

tasks.bootJar {
    archiveFileName = "app.jar"
}

tasks.jar {
    enabled = false // only the executable bootJar is published
}

// Every resolved version is locked (gradle.lockfile), and every artifact is checked against its
// checksum in gradle/verification-metadata.xml; the supply-chain-pins workflow writes and refreshes both.
dependencyLocking { lockAllConfigurations() }
