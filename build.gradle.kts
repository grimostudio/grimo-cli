plugins {
	java
	id("org.springframework.boot") version "4.0.4"
	id("io.spring.dependency-management") version "1.1.7"
	id("org.graalvm.buildtools.native") version "0.11.5"
}

group = "io.github.samzhu"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
	maven {
		url = uri("https://central.sonatype.com/repository/maven-snapshots/")
		mavenContent { snapshotsOnly() }
	}
	maven {
		url = uri("https://repo.spring.io/snapshot")
		mavenContent { snapshotsOnly() }
	}
}

extra["springAiVersion"] = "2.0.0-M3"
extra["springModulithVersion"] = "2.0.3"
extra["springShellVersion"] = "4.0.1"

dependencies {
    // Core
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.modulith:spring-modulith-events-api")
    implementation("org.springframework.shell:spring-shell-starter-ffm")

    // Spring AI Community — agent-client 0.11.0 GA (Maven Central)
    // Known issue: MCP Options Bug (see docs/superpowers/specs/2026-03-31-sdk-mcp-options-bug.md)
    implementation("org.springaicommunity.agents:agent-model:0.11.0")
    implementation("org.springaicommunity.agents:agent-client-core:0.11.0")
    implementation("org.springaicommunity.agents:agent-claude:0.11.0")
    implementation("org.springaicommunity.agents:agent-gemini:0.11.0")
    implementation("org.springaicommunity.agents:agent-codex:0.11.0")

    // Channels
    implementation("org.telegram:telegrambots-springboot-longpolling-starter:9.0.0")
    implementation("com.linecorp.bot:line-bot-messaging-api-client:9.8.0")

    // Modulith runtime
    runtimeOnly("org.springframework.modulith:spring-modulith-actuator")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.springframework.shell:spring-shell-starter-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.springaicommunity:agent-sandbox-docker:0.9.1-SNAPSHOT")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
		mavenBom("org.springframework.shell:spring-shell-dependencies:${property("springShellVersion")}")
		mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

springBoot {
	mainClass.set("io.github.samzhu.grimo.GrimoApplication")
}

// GraalVM native image 需要 --install-exit-handlers 才能讓 shutdown hook 在 SIGINT/SIGTERM 時執行
// 參考：https://github.com/oracle/graal/issues/465
graalvmNative {
	binaries {
		named("main") {
			buildArgs.add("--install-exit-handlers")
		}
	}
}

// FFM terminal provider 需要 native access，寫入 jar manifest 讓 java -jar 自動啟用
// Reference: https://docs.spring.io/spring-shell/reference/building.html
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
	manifest {
		attributes("Enable-Native-Access" to "ALL-UNNAMED")
	}
}
