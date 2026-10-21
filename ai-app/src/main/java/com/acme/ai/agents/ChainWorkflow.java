package com.acme.ai.agents;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import com.acme.ai.config.AiClients;

/**
 * Pattern 1 - Prompt chaining. A fixed sequence of focused LLM calls where each step's output is
 * the next step's input. Trades latency for quality: small, single-purpose prompts are easier
 * for a (small) model to get right than one big prompt.
 * <p>
 * Use case: marketing copy pipeline turning raw product notes into launch copy.
 */
@Component
public class ChainWorkflow {

	private static final List<String[]> STEPS = List.of(
			new String[] { "1. Extract features",
					"From the product notes, list the 5 most important customer-facing features, each with its benefit, as short bullet points. Output only the bullets." },
			new String[] { "2. Draft announcement",
					"Write a 120-word product announcement for small-business customers based on these features. Output only the announcement." },
			new String[] { "3. Brand voice",
					"Rewrite the text in Acme's brand voice: confident, warm, plain English, no jargon, no exclamation marks, short sentences. Output only the rewritten text." },
			new String[] { "4. Final copy",
					"Add a headline of at most 8 words and a one-line call to action. Format as Markdown: '# headline', the body, then the call to action in bold. Output only the Markdown." });

	private final ChatClient chatClient;

	public ChainWorkflow(AiClients aiClients) {
		this.chatClient = aiClients.forFeature("agent-chain").build();
	}

	public List<AgentStep> run(String productNotes) {
		List<AgentStep> steps = new ArrayList<>();
		String current = productNotes;
		for (String[] step : STEPS) {
			String input = current;
			AgentStep result = AgentStep.timed(step[0],
					() -> this.chatClient.prompt().system(step[1]).user(input).call().content());
			steps.add(result);
			current = result.output();
		}
		return steps;
	}

}
