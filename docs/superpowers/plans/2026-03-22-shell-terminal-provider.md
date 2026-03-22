# Shell Terminal Provider 設定 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 加入 Spring Shell terminal provider，讓互動式 Shell prompt 正確顯示。

**Architecture:** `spring-shell-starter` 已包含 JLine 整合，但 JLine 需要一個原生終端提供者才能連接終端。Spring Shell 4.0 提供四種選擇（ffm/jansi/jna/jni），因為專案使用 Java 25，選用 `spring-shell-starter-ffm`（Foreign Function & Memory API，Java 22+ 原生支援，無需額外原生程式庫）。同時設定 `bootRun` task 傳遞 stdin 給 JVM，讓 `./gradlew bootRun` 也能互動。

**Tech Stack:** Spring Shell 4.0.1, Java 25 FFM API

**Reference:**
- [Spring Shell Getting Started](https://docs.spring.io/spring-shell/reference/getting-started.html)
- [Spring Shell v4 Migration Guide](https://github.com/spring-projects/spring-shell/wiki/v4-migration-guide)

---

## File Structure

| File | Responsibility |
|------|----------------|
| `build.gradle.kts` | Modify: 加入 terminal provider dependency + bootRun stdin 設定 |

---

## Task 1: 加入 Terminal Provider 和 bootRun 設定

**Files:**
- Modify: `build.gradle.kts`

- [ ] **Step 1: 加入 `spring-shell-starter-ffm` dependency**

在 `dependencies` 區塊的 `spring-shell-starter` 下方加入：

```kotlin
implementation("org.springframework.shell:spring-shell-starter-ffm")
```

設計說明：FFM（Foreign Function & Memory）是 Java 22+ 原生 API，不需要額外的 Jansi/JNA 原生程式庫，是 Java 25 專案的最佳選擇。

- [ ] **Step 2: 加入 bootRun stdin 設定**

在檔案尾部（`tasks.withType<Test>` 之後）加入：

```kotlin
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    standardInput = System.`in`
}
```

設計說明：Gradle daemon 預設不連接 stdin 到子程序，加入此設定讓 `./gradlew bootRun` 可以互動式輸入。

- [ ] **Step 3: 驗證編譯**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 驗證測試**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 驗證啟動**

Run: `./gradlew bootRun`

Expected:
1. Tomcat 啟動在 port 8080
2. Grimo 啟動流程完成
3. 出現 Spring Shell 互動式 prompt
4. 輸入 `help` 可看到可用指令

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts
git commit -m "build: add spring-shell-starter-ffm terminal provider for interactive prompt"
```
