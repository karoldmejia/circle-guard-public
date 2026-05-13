plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.2.4"))
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:3.2.4"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
}

tasks.named<Test>("test") {
    exclude("**/integration/**")
}

tasks.register<Test>("integrationTest") {
    description = "Runs integration tests."
    group = "verification"

    include("**/integration/**")

    useJUnitPlatform()

    systemProperty(
        "docker.client.strategy",
        "org.testcontainers.dockerclient.UnixSocketClientProviderStrategy"
    )
    systemProperty("docker.host", "unix:///var/run/docker.sock")
    systemProperty("docker.api.version", "1.44")

    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat =
            org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
tasks.named("check") {
    dependsOn("integrationTest")
}