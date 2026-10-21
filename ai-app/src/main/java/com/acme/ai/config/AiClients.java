package com.acme.ai.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import com.acme.ai.observability.FeatureMetricsAdvisor;

/**
 * Hands each demo its own {@link ChatClient.Builder}, pre-wired with per-feature metrics.
 * Features only ever depend on {@link ChatClient}, so swapping the model provider is config-only.
 */
@Component
public class AiClients {

	private final ChatClient.Builder builder;

	private final MeterRegistry meterRegistry;

	private final ObservationRegistry observationRegistry;

	private final ModelQuirksAdvisor modelQuirks;

	public AiClients(ChatClient.Builder builder, MeterRegistry meterRegistry, ObservationRegistry observationRegistry,
			AcmeProperties properties) {
		this.builder = builder;
		this.meterRegistry = meterRegistry;
		this.observationRegistry = observationRegistry;
		this.modelQuirks = new ModelQuirksAdvisor(properties.llm().systemPromptSuffix(),
				properties.llm().stripThinking());
	}

	public ChatClient.Builder forFeature(String feature) {
		return this.builder.clone()
			.defaultAdvisors(new FeatureMetricsAdvisor(feature, this.meterRegistry, this.observationRegistry),
					this.modelQuirks);
	}

}
