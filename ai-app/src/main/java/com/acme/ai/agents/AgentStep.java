package com.acme.ai.agents;

/**
 * One visible step of a workflow, shown in the UI so the audience can follow the pattern.
 */
public record AgentStep(String name, String output, long millis) {

	static AgentStep timed(String name, java.util.function.Supplier<String> work) {
		long start = System.nanoTime();
		String output = work.get();
		return new AgentStep(name, output, (System.nanoTime() - start) / 1_000_000);
	}

}
