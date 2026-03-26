package io.github.samzhu.grimo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * 設計說明：
 * - 排除 Spring AI Community agent-client 的 auto-configuration
 * - Grimo 使用 Library 模式手動管理 AgentModel（不靠 auto-config）
 * - auto-config 會在沒有 CLI 時拋 Exception（如 CodexSDKException），導致啟動失敗
 * - excludeName 只能排除有 AutoConfiguration.imports 註冊的 class
 * - Gemini 沒有 .imports 檔，需用 @ComponentScan excludeFilters 排除
 */
@SpringBootApplication(excludeName = {
	"org.springaicommunity.agents.claude.autoconfigure.ClaudeAgentAutoConfiguration",
	"org.springaicommunity.agents.claude.autoconfigure.ClaudeHookAutoConfiguration",
	"org.springaicommunity.agents.codex.autoconfigure.CodexAgentAutoConfiguration",
	"org.springaicommunity.agents.client.autoconfigure.AgentClientAutoConfiguration"
})
@ComponentScan(excludeFilters = @ComponentScan.Filter(
	type = FilterType.REGEX,
	pattern = "org\\.springaicommunity\\.agents\\..*\\.autoconfigure\\..*"
))
public class GrimoApplication {

	public static void main(String[] args) {
		SpringApplication.run(GrimoApplication.class, args);
	}

}
