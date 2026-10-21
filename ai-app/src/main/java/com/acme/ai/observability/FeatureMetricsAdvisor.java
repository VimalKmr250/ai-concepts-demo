package com.acme.ai.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.core.Ordered;

/**
 * Outermost advisor on every feature's ChatClient. Spring AI already emits {@code gen_ai.*}
 * spans and metrics per model call; this adds the business dimension ("which demo/feature
 * spent the tokens?") that the Grafana dashboard groups by:
 * <ul>
 * <li>an {@code acme.ai.feature} observation (span + timer) wrapping the whole request,
 * including tool-call round trips, tagged with the feature and its outcome;</li>
 * <li>{@code acme.ai.tokens} counters tagged with feature and token type.</li>
 * </ul>
 */
public class FeatureMetricsAdvisor implements CallAdvisor, StreamAdvisor {

	private static final ChatClientMessageAggregator AGGREGATOR = new ChatClientMessageAggregator();

	private final String feature;

	private final ObservationRegistry observationRegistry;

	private final Counter inputTokens;

	private final Counter outputTokens;

	public FeatureMetricsAdvisor(String feature, MeterRegistry meterRegistry,
			ObservationRegistry observationRegistry) {
		this.feature = feature;
		this.observationRegistry = observationRegistry;
		// registered up front (at 0) so rate()/increase() in Prometheus also sees the first request
		this.inputTokens = tokenCounter(meterRegistry, "input");
		this.outputTokens = tokenCounter(meterRegistry, "output");
	}

	private Counter tokenCounter(MeterRegistry registry, String type) {
		return Counter.builder("acme.ai.tokens")
			.description("LLM tokens consumed per showcase feature")
			.tag("feature", this.feature)
			.tag("type", type)
			.register(registry);
	}

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
		Observation observation = newObservation().start();
		try (Observation.Scope scope = observation.openScope()) {
			ChatClientResponse response = chain.nextCall(request);
			recordUsage(response);
			return response;
		}
		catch (RuntimeException ex) {
			recordFailure(observation, ex);
			throw ex;
		}
		finally {
			observation.stop();
		}
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
		Observation observation = newObservation().start();
		return AGGREGATOR.aggregateChatClientResponse(chain.nextStream(request), this::recordUsage)
			.doOnError(ex -> recordFailure(observation, ex))
			.doFinally(signal -> observation.stop());
	}

	private Observation newObservation() {
		return Observation.createNotStarted("acme.ai.feature", this.observationRegistry)
			.contextualName("acme " + this.feature)
			.lowCardinalityKeyValue("feature", this.feature)
			.lowCardinalityKeyValue("outcome", "success");
	}

	private static void recordFailure(Observation observation, Throwable ex) {
		if (ex instanceof ExpectedOutcome expected) {
			observation.lowCardinalityKeyValue("outcome", expected.outcome());
		}
		else {
			observation.lowCardinalityKeyValue("outcome", "error");
			observation.error(ex);
		}
	}

	private void recordUsage(ChatClientResponse response) {
		if (response == null || response.chatResponse() == null) {
			return;
		}
		Usage usage = response.chatResponse().getMetadata().getUsage();
		if (usage == null) {
			return;
		}
		increment(this.inputTokens, usage.getPromptTokens());
		increment(this.outputTokens, usage.getCompletionTokens());
	}

	private static void increment(Counter counter, Integer tokens) {
		if (tokens != null && tokens > 0) {
			counter.increment(tokens);
		}
	}

	@Override
	public String getName() {
		return "FeatureMetricsAdvisor";
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}

}
