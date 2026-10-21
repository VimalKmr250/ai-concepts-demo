package com.acme.ai.agents;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import com.acme.ai.config.AiClients;

/**
 * Pattern 3 - Parallelization (sectioning). Independent sub-tasks run concurrently and the
 * results are aggregated. Java 21+ virtual threads make fan-out over blocking LLM calls cheap.
 * <p>
 * Use case: impact analysis of a planned change for several stakeholder groups at once.
 */
@Component
public class ParallelizationWorkflow {

	static final List<String> STAKEHOLDERS = List.of("Customers", "Employees", "Investors", "Regulators");

	public record ParallelResult(List<AgentStep> analyses, long wallClockMillis, long sumOfCallsMillis) {
	}

	private final ChatClient chatClient;

	public ParallelizationWorkflow(AiClients aiClients) {
		this.chatClient = aiClients.forFeature("agent-parallel").build();
	}

	public ParallelResult run(String announcement) throws Exception {
		long start = System.nanoTime();
		try (ExecutorService executor = TracedExecutors.virtualThreads()) {
			List<Future<AgentStep>> futures = STAKEHOLDERS.stream()
				.map(group -> executor.submit(() -> AgentStep.timed(group, () -> analyze(group, announcement))))
				.toList();
			List<AgentStep> analyses = new java.util.ArrayList<>();
			for (Future<AgentStep> future : futures) {
				analyses.add(future.get());
			}
			long wall = (System.nanoTime() - start) / 1_000_000;
			long sum = analyses.stream().mapToLong(AgentStep::millis).sum();
			return new ParallelResult(analyses, wall, sum);
		}
	}

	private String analyze(String group, String announcement) {
		return this.chatClient.prompt()
			.system("""
					You are a corporate communications analyst at Acme Corp. Analyse how the announcement
					affects %s only. Output exactly: 3 bullet points on the impact, then a final line
					'Risk: LOW|MEDIUM|HIGH - <one-sentence mitigation>'.""".formatted(group))
			.user(announcement)
			.call()
			.content();
	}

}
