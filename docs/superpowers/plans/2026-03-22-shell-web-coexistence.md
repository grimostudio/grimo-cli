# Shell + Web 並存啟動設定 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 讓 Grimo 以 Spring Shell 互動式 prompt 為主要介面，同時在背景執行 embedded Tomcat 提供 HTTP 端點（LINE webhook、actuator）。

**Architecture:** Spring Shell 4.0 的 ShellRunner 與 WebMVC 可以並存於同一個 ApplicationContext。Shell 在獨立執行緒讀取 stdin，Tomcat 在背景執行緒處理 HTTP。只需在 `application.yaml` 明確宣告 `spring.main.web-application-type=servlet` 和 `spring.shell.interactive.enabled=true`，並設定 Web server port。

**Tech Stack:** Spring Shell 4.0.1, Spring Boot 4.0.4 (WebMVC), Spring Boot Actuator

**Reference:**
- [Spring Shell 4.0 Execution Reference](https://docs.spring.io/spring-shell/reference/execution.html)
- [Spring Boot WebApplicationType](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/WebApplicationType.html)

---

## File Structure

| File | Responsibility |
|------|----------------|
| `src/main/resources/application.yaml` | Modify: 加入 Shell interactive、Web server 和 actuator 設定 |
| `src/test/java/.../GrimoApplicationTests.java` | Verify: context loads with Shell + Web 並存 |

---

## Task 1: 設定 Shell + Web 並存

**Files:**
- Modify: `src/main/resources/application.yaml`

- [ ] **Step 1: 更新 application.yaml**

加入以下設定，確保 Shell 互動模式和 Web server 並存：

```yaml
spring:
  application:
    name: grimo
  main:
    web-application-type: servlet
  shell:
    interactive:
      enabled: true

server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health,info,modulith

grimo:
  workspace: ${user.home}/grimo-workspace
```

設計說明：
- `spring.main.web-application-type=servlet`：明確啟動 embedded Tomcat（雖然有 webmvc 在 classpath 時會自動偵測為 servlet，但明確宣告避免未來誤解）
- `spring.shell.interactive.enabled=true`：確保啟動互動式 Shell prompt（Spring Shell 4.0 預設即為 true，但明確宣告意圖）
- `server.port=8080`：HTTP 端點 port，供 LINE webhook 和 actuator 使用
- `management.endpoints.web.exposure.include`：僅暴露必要的 actuator 端點

- [ ] **Step 2: 驗證啟動**

Run: `./gradlew bootRun`

Expected:
1. 看到 Tomcat 啟動 log：`Tomcat started on port 8080`
2. 看到 Grimo 啟動流程 log：`Grimo is ready.`
3. 看到 Spring Shell 互動式 prompt（如 `shell:>` 或類似）
4. 可在 prompt 中輸入 `status` 指令並得到回應
5. 同時可用瀏覽器存取 `http://localhost:8080/actuator/health`

- [ ] **Step 3: 執行測試**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL（所有測試通過）

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/application.yaml
git commit -m "config: enable Shell + Web coexistence with explicit settings"
```
