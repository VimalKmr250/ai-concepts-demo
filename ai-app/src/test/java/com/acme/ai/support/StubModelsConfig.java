package com.acme.ai.support;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Replaces the LLM and the embedding model in integration tests: a scripted chat model and a
 * deterministic bag-of-words embedding model. Use with {@code spring.ai.model.chat=none} and
 * {@code spring.ai.model.embedding=none}. Tests sharing this exact setup share one Spring
 * context (and one Postgres container).
 */
@TestConfiguration(proxyBeanMethods = false)
public class StubModelsConfig {

	@Bean
	public StubChatModel chatModel() {
		return new StubChatModel();
	}

	@Bean
	public EmbeddingModel embeddingModel() {
		return new HashingEmbeddingModel();
	}

}
