# GraalVM Native Image 配置指南

## 概述

Grimo CLI 使用 Spring Boot 4.0 + GraalVM Native Image 支援（`org.graalvm.buildtools.native` plugin）。
本文件記錄 Native Image / AOT 編譯過程中遇到的問題及解決方案。

---

## 已解決的問題

### 1. Spring AI Community Agent Client Auto-Configuration 干擾 AOT

**錯誤訊息**：
```
CodexSDKException: Codex CLI not found. Please install via 'npm install -g @openai/codex'
```

**根本原因**：

```
依賴鏈：
spring-ai-codex-agent-0.10.0-SNAPSHOT.jar
  └── META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
        └── CodexAgentAutoConfiguration
              └── @Bean codexClient() → CodexClient.create()
                    └── CodexCliDiscovery.discoverCodexCli() → 拋 Exception
```

- Grimo 使用 **Library 模式**（非 Starter）手動管理 AgentModel
- 但 agent-client JAR 內含 `AutoConfiguration.imports` 檔案
- Spring Boot AOT `processAot` 在 build 時執行 `refreshForAotProcessing()`
- auto-config 被發現後嘗試建立 bean → CLI 不存在 → 爆炸

**為什麼不能直接排除 JAR？**

Grimo 的 `AgentConfiguration` 需要用到 `ClaudeAgentModel`、`GeminiAgentModel`、`CodexAgentModel` 等 class。排除 JAR 會導致編譯失敗。

**解決方案**：

在 `application.yaml` 中使用 `spring.autoconfigure.exclude`（同時對 runtime 和 AOT `refreshForAotProcessing()` 生效）：

```yaml
spring:
  autoconfigure:
    exclude:
      - org.springaicommunity.agents.claude.autoconfigure.ClaudeAgentAutoConfiguration
      - org.springaicommunity.agents.claude.autoconfigure.ClaudeHookAutoConfiguration
      - org.springaicommunity.agents.codex.autoconfigure.CodexAgentAutoConfiguration
      - org.springaicommunity.agents.client.autoconfigure.AgentClientAutoConfiguration
```

**為什麼不用 `@SpringBootApplication(excludeName = ...)`？**

嘗試過，但 `GeminiAgentAutoConfiguration` 沒有在 `AutoConfiguration.imports` 中註冊，Spring Boot 認為它不是 auto-configuration class，用 `excludeName` 排除就會報錯：

```
IllegalStateException: The following classes could not be excluded because they are not auto-configuration classes:
- org.springaicommunity.agents.gemini.autoconfigure.GeminiAgentAutoConfiguration
```

`spring.autoconfigure.exclude` 只排除有 `.imports` 註冊的 class，不會觸發這個問題。Gemini 的 auto-config 沒有 `.imports` 檔，不會被 Spring Boot 自動載入，所以不需要排除。

**參考資料**：
- [Spring Framework AOT Processing](https://docs.spring.io/spring-framework/reference/core/aot.html)
- [Spring Boot Auto-configuration Exclusion](https://docs.spring.io/spring-boot/reference/using/auto-configuration.html)

---

## 未來可能需要的配置

### RuntimeHints（Native Image 編譯時）

目前 Grimo 使用 JVM 模式，不需要 RuntimeHints。但如果未來要 `./gradlew nativeCompile`，可能需要：

1. **反射註冊** — agent-client SDK 內部可能用反射建構物件
2. **資源註冊** — SDK 可能需要讀取 classpath 資源
3. **代理註冊** — Spring AI 的介面可能需要 JDK Proxy

參考 gate 專案的模式：

```java
@Configuration
@ImportRuntimeHints(NativeImageHints.AgentClientHints.class)
@RegisterReflectionForBinding({
    // 需要反射的 SDK 類別（待 native compile 時確認）
})
public class NativeImageHints {

    static class AgentClientHints implements RuntimeHintsRegistrar {
        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            // 需要的資源模式（待 native compile 時確認）
        }
    }
}
```

### AOT 專用 Profile

如果未來 AOT 需要特殊配置（例如 gate 的 `application-aot.yaml`），可建立 `src/main/resources/application-aot.yaml`：

```yaml
# AOT 編譯專用配置
# 用途: ./gradlew processAot -Dspring.profiles.active=aot
# 不要在執行時使用此 profile
```

---

## 除錯技巧

### 1. AOT 處理日誌

查看 AOT 處理過程：
```bash
./gradlew processAot --info
```

### 2. 確認 auto-config 排除生效

查看 Spring Boot 的 auto-config 報告：
```bash
java -jar build/libs/grimo-0.0.1-SNAPSHOT.jar --debug 2>&1 | grep "exclude"
```

### 3. 查看生成的 AOT 配置

```bash
cat build/generated/aotSources/io/github/samzhu/grimo/GrimoApplication__BeanDefinitions.java
```

### 4. 反射配置檢查（Native Image）

```bash
cat build/resources/aot/META-INF/native-image/io.github.samzhu/grimo/reflect-config.json
```

---

## 相關文件

- [Spring Boot Native Image Support](https://docs.spring.io/spring-boot/reference/packaging/native-image/index.html)
- [Spring Framework AOT Processing](https://docs.spring.io/spring-framework/reference/core/aot.html)
- [GraalVM Native Image Reference](https://www.graalvm.org/latest/reference-manual/native-image/)
- [gate 專案 GRAALVM-NATIVE-IMAGE.md](/Users/samzhu/workspace/github-samzhu/gate/docs/GRAALVM-NATIVE-IMAGE.md)
