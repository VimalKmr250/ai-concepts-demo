package com.acme.ai.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Demo tuning knobs from the {@code acme.*} section of application.yml.
 */
@ConfigurationProperties("acme")
public record AcmeProperties(@DefaultValue Llm llm, @DefaultValue Rag rag, @DefaultValue Guardrails guardrails,
		@DefaultValue Extraction extraction, @DefaultValue Agents agents, @DefaultValue Evaluation evaluation) {

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
