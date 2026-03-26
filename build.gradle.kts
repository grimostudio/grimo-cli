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

    // Observability
    implementation("org.springframework.boot:spring-boot-micrometer-tracing-brave")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("io.micrometer:micrometer-tracing-bridge-brave")

    // Spring AI Community — agent-client libraries (Library mode, NOT starter)
    implementation("org.springaicommunity.agents:spring-ai-agent-model:0.10.0-SNAPSHOT")
    implementation("org.springaicommunity.agents:spring-ai-agent-client:0.10.0-SNAPSHOT")
    implementation("org.springaicommunity.agents:spring-ai-claude-agent:0.10.0-SNAPSHOT")
    implementation("org.springaicommunity.agents:spring-ai-gemini:0.10.0-SNAPSHOT")
    implementation("org.springaicommunity.agents:spring-ai-codex-agent:0.10.0-SNAPSHOT")

    // Channels
    implementation("org.telegram:telegrambots-springboot-longpolling-starter:9.0.0")
    implementation("com.linecorp.bot:line-bot-messaging-api-client:9.8.0")

    // Modulith runtime
    runtimeOnly("org.springframework.modulith:spring-modulith-actuator")
    runtimeOnly("org.springframework.modulith:spring-modulith-observability")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-micrometer-tracing-test")
    testImplementation("org.springframework.boot:spring-boot-starter-opentelemetry-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.ai:spring-ai-spring-boot-testcontainers")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.springframework.shell:spring-shell-starter-test")
    testImplementation("org.testcontainers:testcontainers-grafana")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
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

// 停用 AOT 處理：agent-client auto-config 在 AOT 時會嘗試偵測 CLI 並拋異常
// 設計說明：Grimo 使用 Library 模式手動管理 AgentModel，不需要 AOT 預處理
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
	jvmArgs = listOf("-Dspring.aot.enabled=false")
}

springBoot {
	mainClass.set("io.github.samzhu.grimo.GrimoApplication")
}

// FFM terminal provider 需要 native access，寫入 jar manifest 讓 java -jar 自動啟用
// Reference: https://docs.spring.io/spring-shell/reference/building.html
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
	manifest {
		attributes("Enable-Native-Access" to "ALL-UNNAMED")
	}
}
