package com.acme.ai.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Demo tuning knobs from the {@code acme.*} section of application.yml.
 */
@ConfigurationProperties("acme")
public record AcmeProperties(@DefaultValue Llm llm, @DefaultValue Rag rag, @DefaultValue Guardrails guardrails,
		@DefaultValue Extraction extraction, @DefaultValue Agents agents, @DefaultValue Evaluation evaluation,
		@DefaultValue Sql sql, @DefaultValue Cache cache, @DefaultValue A2a a2a) {

	/**
	 * Remote agents reached over the A2A protocol (demo 12).
	 */
	public record A2a(@DefaultValue("http://localhost:8082") String procurementUrl) {
	}

	/**
	 * Semantic cache for the RAG demo (demo 11).
	 */
	public record Cache(@DefaultValue("true") boolean enabled, @DefaultValue("0.95") double similarityThreshold,
			@DefaultValue("24h") java.time.Duration ttl) {
	}

	/**
	 * Text-to-SQL limits (demo 10).
	 */
	public record Sql(@DefaultValue({ "employee", "asset", "ticket" }) List<String> allowedTables,
			@DefaultValue("200") int maxRows, @DefaultValue("3") int timeoutSeconds) {
	}

	public record Evaluation(@DefaultValue("0.8") double minPassRate) {
	}

	/**
	 * Model-specific prompt tweaks, applied by {@link ModelQuirksAdvisor}.
	 */
	public record Llm(@DefaultValue("") String systemPromptSuffix, @DefaultValue("false") boolean stripThinking) {
	}

	public record Rag(@DefaultValue("4") int topK, @DefaultValue("0.55") double similarityThreshold,
			@DefaultValue("false") boolean rewriteQuery, @DefaultValue("true") boolean ingestOnStartup) {
	}

	public record Guardrails(@DefaultValue("false") boolean llmInjectionClassifier,
			@DefaultValue List<String> allowedTopics, @DefaultValue List<String> denylist) {
	}

	public record Extraction(@DefaultValue("2") int maxRetries) {
	}

	public record Agents(@DefaultValue("3") int evaluatorMaxRounds) {
	}

}
