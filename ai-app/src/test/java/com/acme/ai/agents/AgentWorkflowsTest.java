package com.acme.ai.agents;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;

import com.acme.ai.agents.ParallelizationWorkflow.ParallelResult;
import com.acme.ai.support.StubChatModel;
import com.acme.ai.support.TestFixtures;

import static org.assertj.core.api.Assertions.assertThat;

class AgentWorkflowsTest {

	@Test
	void chainFeedsEachStepIntoTheNext() {
		StubChatModel model = new StubChatModel().reply("features").reply("draft").reply("polished").reply("# Final");

		List<AgentStep> steps = new ChainWorkflow(TestFixtures.aiClients(model)).run("raw notes");

		assertThat(steps).extracting(AgentStep::output).containsExactly("features", "draft", "polished", "# Final");
		assertThat(model.prompts()).extracting(p -> p.getUserMessage().getText())
			.containsExactly("raw notes", "features", "draft", "polished");
	}

	@Test
	void parallelizationRunsStakeholderAnalysesConcurrently() throws Exception {
		StubChatModel model = new StubChatModel().otherwise(prompt -> {
			sleep(400);
			return new AssistantMessage("analysis");
		});

		ParallelResult result = new ParallelizationWorkflow(TestFixtures.aiClients(model)).run("price increase");

		assertThat(result.analyses()).extracting(AgentStep::name).containsExactlyElementsOf(ParallelizationWorkflow.STAKEHOLDERS);
		assertThat(result.sumOfCallsMillis()).isGreaterThanOrEqualTo(1600);
		assertThat(result.wallClockMillis()).isLessThan(1200);
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
