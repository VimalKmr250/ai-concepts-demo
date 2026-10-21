package com.acme.ai.helpdesk;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.model.tool.ToolCallingManager;

import com.acme.ai.helpdesk.HelpdeskAgent.AgentResult;
import com.acme.ai.helpdesk.HelpdeskAgent.Status;
import com.acme.ai.support.StubChatModel;
import com.acme.ai.support.TestFixtures;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Human-in-the-loop flow with a scripted model: no LLM and no database needed.
 */
class HelpdeskAgentTest {

	private final StubChatModel model = new StubChatModel();

	private final FakeRepository repository = new FakeRepository();

	private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

	private final HelpdeskAgent agent = new HelpdeskAgent(TestFixtures.aiClients(this.model, this.meters),
			new HelpdeskTools(this.repository), ToolCallingManager.builder().build(), new PendingApprovalStore(),
			this.repository, this.meters);

	@Test
	void sensitiveToolsAreDiscoveredFromAnnotations() {
		assertThat(HelpdeskAgent.toolsRequiringApproval(HelpdeskTools.class)).containsOnlyKeys("resetPassword",
				"assignLoanerLaptop");
	}

	@Test
	void safeToolsRunAutomatically() {
		this.model.replyToolCall("c1", "lookupEmployee", "{\"username\":\"jdoe\"}").reply("John works in Berlin.");

		AgentResult result = this.agent.handle("Where does jdoe work?");

		assertThat(result.status()).isEqualTo(Status.COMPLETED);
		assertThat(result.reply()).isEqualTo("John works in Berlin.");
		assertThat(result.toolTrace()).singleElement().satisfies(t -> {
			assertThat(t.tool()).isEqualTo("lookupEmployee");
			assertThat(t.mode()).isEqualTo("auto");
			assertThat(t.result()).contains("Berlin");
		});
	}

	@Test
	void sensitiveToolWaitsForApprovalThenRuns() {
		this.model.replyToolCall("c1", "resetPassword", "{\"username\":\"jdoe\",\"reason\":\"forgot it\"}")
			.reply("Done - a reset link was sent.");

		AgentResult pending = this.agent.handle("Reset the password for jdoe");

		assertThat(pending.status()).isEqualTo(Status.PENDING_APPROVAL);
		assertThat(pending.proposedCalls()).singleElement().satisfies(c -> assertThat(c.tool()).isEqualTo("resetPassword"));
		assertThat(this.repository.actions).doesNotContain("RESET_PASSWORD");
		assertThat(this.agent.pending()).hasSize(1);

		AgentResult done = this.agent.approve(pending.approvalId(), "supervisor");

		assertThat(done.status()).isEqualTo(Status.COMPLETED);
		assertThat(done.reply()).isEqualTo("Done - a reset link was sent.");
		assertThat(this.repository.actions).containsExactly("RESET_PASSWORD", "APPROVED");
		assertThat(toolResponses(this.model.lastPrompt().getInstructions())).contains("Password reset completed");
		assertThat(this.agent.pending()).isEmpty();
	}

	@Test
	void rejectedToolIsNotExecutedAndTheModelIsTold() {
		this.model.replyToolCall("c1", "resetPassword", "{\"username\":\"jdoe\",\"reason\":\"forgot it\"}")
			.reply("Sorry, the reset was not approved.");

		AgentResult pending = this.agent.handle("Reset the password for jdoe");
		AgentResult done = this.agent.reject(pending.approvalId(), "supervisor", "needs manager ticket");

		assertThat(done.reply()).isEqualTo("Sorry, the reset was not approved.");
		assertThat(done.toolTrace()).singleElement().satisfies(t -> assertThat(t.mode()).isEqualTo("rejected"));
		assertThat(this.repository.actions).containsExactly("REJECTED");
		assertThat(toolResponses(this.model.lastPrompt().getInstructions())).contains("REJECTED", "needs manager ticket");
	}

	private static String toolResponses(List<Message> messages) {
		return messages.stream()
			.filter(ToolResponseMessage.class::isInstance)
			.flatMap(m -> ((ToolResponseMessage) m).getResponses().stream())
			.map(ToolResponseMessage.ToolResponse::responseData)
			.reduce("", String::concat);
	}

	/** In-memory stand-in for the JDBC repository. */
	static class FakeRepository extends HelpdeskRepository {

		final List<String> actions = new ArrayList<>();

		FakeRepository() {
			super(null);
		}

		@Override
		public Optional<Employee> findEmployee(String username) {
			return "jdoe".equals(username) ? Optional
				.of(new Employee("jdoe", "John Doe", "john.doe@acme.example", "Engineering", "Berlin", "mgarcia"))
					: Optional.empty();
		}

		@Override
		public List<Asset> assetsOf(String username) {
			return List.of(new Asset("LAP-2041", "LAPTOP", "Dell Latitude 7440", username, LocalDate.of(2024, 3, 11),
					LocalDate.of(2027, 3, 10), "IN_USE"));
		}

		@Override
		public void audit(String actor, String action, String details) {
			this.actions.add(action);
		}

		@Override
		public List<AuditEntry> auditLog() {
			return this.actions.stream().map(a -> new AuditEntry("test", a, "", LocalDateTime.now())).toList();
		}

	}

}
