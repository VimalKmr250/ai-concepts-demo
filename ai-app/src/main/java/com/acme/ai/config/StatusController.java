package com.acme.ai.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Which model/provider the showcase is currently wired to - shown in the UI header so it is
 * obvious when the same demos run against a different provider.
 */
@RestController
public class StatusController {

	private final Environment environment;

	private final String baseUrl;

	private final String chatModel;

	private final String embeddingModel;

	private final String mcpUrl;

	public StatusController(Environment environment, @Value("${spring.ai.openai.base-url:}") String baseUrl,
			@Value("${spring.ai.openai.chat.model:}") String chatModel,
			@Value("${spring.ai.openai.embedding.model:}") String embeddingModel,
			@Value("${spring.ai.mcp.client.streamable-http.connections.travel-desk.url:}") String mcpUrl) {
		this.environment = environment;
		this.baseUrl = baseUrl;
		this.chatModel = chatModel;
		this.embeddingModel = embeddingModel;
		this.mcpUrl = mcpUrl;
	}

	@GetMapping("/api/status")
	public Status status() {
		return new Status(this.chatModel, this.embeddingModel, this.baseUrl,
				Arrays.asList(this.environment.getActiveProfiles()), this.mcpUrl + "/mcp", "http://localhost:3000");
	}

	public record Status(String chatModel, String embeddingModel, String llmBaseUrl, List<String> profiles,
			String mcpServer, String grafanaUrl) {
	}

}
