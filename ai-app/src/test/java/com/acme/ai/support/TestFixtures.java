package com.acme.ai.support;

import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import com.acme.ai.config.AcmeProperties;
import com.acme.ai.config.AiClients;

/**
 * Builders for unit tests that need the app's wiring without a Spring context.
 */
public final class TestFixtures {

	private TestFixtures() {
	}

	public static AcmeProperties properties() {
		return new AcmeProperties(new AcmeProperties.Llm("", true), new AcmeProperties.Rag(4, 0.55, false, false),
				new AcmeProperties.Guardrails(false, List.of("banking", "cards", "payments", "loans"),
						List.of("confidential pricing", "system prompt")),
				new AcmeProperties.Extraction(2), new AcmeProperties.Agents(3), new AcmeProperties.Evaluation(0.8),
				new AcmeProperties.Sql(List.of("employee", "asset", "ticket"), 200, 3),
				new AcmeProperties.Cache(true, 0.95, java.time.Duration.ofHours(24)),
				new AcmeProperties.A2a("http://localhost:8082"));
	}

	public static AiClients aiClients(ChatModel chatModel) {
		return aiClients(chatModel, new SimpleMeterRegistry());
	}

	public static AiClients aiClients(ChatModel chatModel, MeterRegistry meterRegistry) {
		return new AiClients(ChatClient.builder(chatModel), meterRegistry, ObservationRegistry.NOOP, properties());
	}

}
