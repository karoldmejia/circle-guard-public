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
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-ldap")
    implementation("org.springframework.security:spring-security-ldap")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:testcontainers:1.20.6")
    testImplementation("org.testcontainers:junit-jupiter:1.20.6")
    testImplementation("org.testcontainers:postgresql:1.20.6")
    testImplementation("org.testcontainers:testcontainers:1.20.6")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
testImplementation("com.github.docker-java:docker-java-core:3.4.1")
testImplementation("com.github.docker-java:docker-java-transport-httpclient5:3.4.1")

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