package com.acme.ai.agents;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import com.acme.ai.config.AcmeProperties;
import com.acme.ai.config.AiClients;

/**
 * Pattern 5 - Evaluator-optimizer. A generator produces a solution, an evaluator grades it
 * against explicit criteria (structured output), and the generator revises using the feedback
 * until the evaluator passes it or the round limit is reached.
 * <p>
 * Use case: Java code generation with an automated reviewer.
 */
@Component
public class EvaluatorOptimizerWorkflow {

	public enum Verdict {

		PASS, NEEDS_IMPROVEMENT

	}

	/**
	 * Feedback comes first so the reviewer reasons before giving the verdict.
	 */
	public record Evaluation(
			@JsonPropertyDescription("Specific review comments per criterion; for NEEDS_IMPROVEMENT list what to change") String feedback,
			Verdict verdict) {
	}

	public record Round(int round, String solution, Verdict verdict, String feedback, long millis) {
	}

	public record EvaluatorResult(List<Round> rounds, boolean passed, String finalSolution) {
	}

	private static final String GENERATOR = """
			You are a senior Java developer. Write a single, complete Java 21 static method (with any
			small private helpers it needs) that solves the task. Include Javadoc. Handle null and
			invalid input. Output only the Java code in a ```java block.""";

	private static final String EVALUATOR = """
			You are a strict Java code reviewer. Evaluate the code for the task against these criteria:
			1. correctness of the algorithm, 2. null/invalid input handling, 3. readability and naming,
			4. no unnecessary complexity.
			Return JSON with two fields:
			"feedback": your review - for each problem, what is wrong and how to fix it (max 4 points);
			if everything is fine, say briefly why;
			"verdict": PASS only if all criteria are fully met, otherwise NEEDS_IMPROVEMENT.""";

	private final ChatClient chatClient;

	private final int maxRounds;

	public EvaluatorOptimizerWorkflow(AiClients aiClients, AcmeProperties properties) {
		this.chatClient = aiClients.forFeature("agent-evaluator").build();
		this.maxRounds = properties.agents().evaluatorMaxRounds();
	}

	public EvaluatorResult run(String task) {
		List<Round> rounds = new ArrayList<>();
		String solution = null;
		String feedback = null;
		for (int round = 1; round <= this.maxRounds; round++) {
			long start = System.nanoTime();
			String prompt = feedback == null ? "Task: " + task
					: "Task: %s%n%nYour previous attempt:%n%s%n%nReviewer feedback to address:%n%s".formatted(task,
							solution, feedback);
			solution = this.chatClient.prompt().system(GENERATOR).user(prompt).call().content();

			Evaluation evaluation = this.chatClient.prompt()
				.system(EVALUATOR)
				.user("Task: %s%n%nCode:%n%s".formatted(task, solution))
				.call()
				.entity(Evaluation.class, spec -> spec.useProviderStructuredOutput());
			Verdict verdict = evaluation != null && evaluation.verdict() != null ? evaluation.verdict()
					: Verdict.NEEDS_IMPROVEMENT;
			feedback = evaluation != null ? evaluation.feedback() : "Reviewer returned no feedback";
			rounds.add(new Round(round, solution, verdict, feedback, (System.nanoTime() - start) / 1_000_000));
			if (verdict == Verdict.PASS) {
				return new EvaluatorResult(rounds, true, solution);
			}
		}
		return new EvaluatorResult(rounds, false, solution);
	}

}
