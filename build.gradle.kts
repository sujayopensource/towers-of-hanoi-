plugins {
    id("java-library")
    id("io.spring.dependency-management") version "1.1.4"
    id("maven-publish")
}

group = "io.towersofhanoi"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withJavadocJar()
    withSourcesJar()
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.2.5")
    }
}

dependencies {
    // Caffeine L1 — always required
    api("com.github.ben-manes.caffeine:caffeine:3.1.8")

    // Spring Boot autoconfigure — compileOnly; consumers bring their own version
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework:spring-context-support")

    // Redis L2 — optional; library degrades gracefully without it
    compileOnly("org.springframework.boot:spring-boot-starter-data-redis")

    // Jackson for Redis serialization
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // Micrometer — optional; metrics only wired when present
    compileOnly("io.micrometer:micrometer-core")

    // IDE/documentation support for @ConfigurationProperties
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis")
    testImplementation("org.springframework.boot:spring-boot-starter")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("io.micrometer:micrometer-core")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name.set("Towers of Hanoi — Spring Boot Cache")
                description.set("Two-tier Caffeine + Redis caching library with Spring Boot auto-configuration")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
            }
        }
    }
}
