package com.acme.ai.agents;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import com.acme.ai.config.AiClients;

/**
 * Pattern 4 - Orchestrator-workers. An orchestrator LLM decides at runtime which sub-tasks are
 * needed (returned as structured output); worker LLM calls then execute each sub-task. Unlike
 * parallelization, the sub-tasks are not known in advance.
 * <p>
 * Use case: turning a product-launch brief into a content kit.
 */
@Component
public class OrchestratorWorkersWorkflow {

	public record WorkerTask(String type, String description) {
	}

	public record Plan(String analysis, List<WorkerTask> tasks) {
	}

	public record OrchestratorResult(Plan plan, List<AgentStep> outputs) {
	}

	private static final int MAX_TASKS = 4;

	private final ChatClient chatClient;

	public OrchestratorWorkersWorkflow(AiClients aiClients) {
		this.chatClient = aiClients.forFeature("agent-orchestrator").build();
	}

	public OrchestratorResult run(String brief) throws Exception {
		Plan plan = this.chatClient.prompt()
			.system("""
					You are the content lead at Acme Corp. Read the launch brief and plan the content kit.
					Return a short analysis of the audience and key message, and 2 to 4 tasks.
					Each task has a type (for example "Blog intro", "LinkedIn post", "Customer email",
					"Press quote") and a one-sentence description of what the writer must produce.""")
			.user(brief)
			.call()
			.entity(Plan.class, spec -> spec.useProviderStructuredOutput());
		if (plan == null || plan.tasks() == null || plan.tasks().isEmpty()) {
			throw new IllegalStateException("The orchestrator returned no tasks");
		}
		List<WorkerTask> tasks = plan.tasks().stream().limit(MAX_TASKS).toList();

		try (ExecutorService executor = TracedExecutors.virtualThreads()) {
			List<Future<AgentStep>> futures = tasks.stream()
				.map(task -> executor.submit(() -> AgentStep.timed(task.type(), () -> work(brief, plan, task))))
				.toList();
			List<AgentStep> outputs = new ArrayList<>();
			for (Future<AgentStep> future : futures) {
				outputs.add(future.get());
			}
			return new OrchestratorResult(new Plan(plan.analysis(), tasks), outputs);
		}
	}

	private String work(String brief, Plan plan, WorkerTask task) {
		return this.chatClient.prompt()
			.system("""
					You are a copywriter at Acme Corp. Produce exactly the requested piece, ready to publish,
					in at most 150 words. Output only the content.""")
			.user("""
					Launch brief:
					%s

					Content lead's analysis: %s

					Your task (%s): %s""".formatted(brief, plan.analysis(), task.type(), task.description()))
			.call()
			.content();
	}

}
