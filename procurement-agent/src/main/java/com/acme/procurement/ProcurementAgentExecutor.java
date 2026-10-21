package com.acme.procurement;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.server.tasks.TaskUpdater;
import io.a2a.spec.DataPart;
import io.a2a.spec.JSONRPCError;
import io.a2a.spec.Message;
import io.a2a.spec.Task;
import io.a2a.spec.TaskNotCancelableError;
import io.a2a.spec.TaskState;
import io.a2a.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import com.acme.procurement.Ledger.PurchaseRequest;
import com.acme.procurement.ProcurementTools.BudgetCheck;

/**
 * Runs one A2A task: the A2A SDK calls {@link #execute} for every {@code message/send}.
 * <p>
 * The task moves through {@code submitted -> working -> completed} (or {@code failed} /
 * {@code rejected}). Every tool call publishes another {@code working} status with a short
 * message, so the caller gets a timeline in {@code task.history}. The result is returned as
 * artifacts: a text summary and, when a purchase request was raised, a data part with its JSON.
 * <p>
 * When the caller has not named the paying department, the task stops in {@code input-required}
 * with a question, before any model call. The caller answers with another {@code message/send}
 * carrying the same {@code taskId}; this executor then runs again with the task's history.
 */
@Component
public class ProcurementAgentExecutor implements AgentExecutor {

	private static final Logger log = LoggerFactory.getLogger(ProcurementAgentExecutor.class);

	private static final Pattern THINK_BLOCK = Pattern.compile("(?s)<think>.*?</think>\\s*");

	/** "PR-7892", "PR number 8873", "purchase request 2023PR12345"... */
	private static final Pattern CLAIMS_A_PR = Pattern.compile("(?i)\\b(PR|purchase request)\\b[^.\\n]*\\d");

	private final ChatClient chatClient;

	private final Ledger ledger;

	public ProcurementAgentExecutor(ChatClient procurementChatClient, Ledger ledger) {
		this.chatClient = procurementChatClient;
		this.ledger = ledger;
	}

	@Override
	public void execute(RequestContext context, EventQueue eventQueue) throws JSONRPCError {
		TaskUpdater task = new TaskUpdater(context, eventQueue);
		if (context.getTask() == null) {
			task.submit(status(task, TaskState.SUBMITTED, "Request received"));
		}
		if (text(context.getMessage()).isBlank()) {
			task.reject(status(task, TaskState.REJECTED, "Send the hardware request as a text part."));
			return;
		}
		// Required input is checked in code before the model runs: asked to "order a laptop for
		// tchen", a small model happily guesses which department pays.
		List<String> payers = this.ledger.departmentsNamedIn(callerText(context));
		if (payers.isEmpty()) {
			// not final: the caller answers with another message/send carrying this taskId
			task.requiresInput(status(task, TaskState.INPUT_REQUIRED,
					"Which department pays for this? One of: " + String.join(", ", this.ledger.departments()) + "."));
			return;
		}
		task.startWork(status(task, TaskState.WORKING, context.getTask() == null ? "Planning the purchase"
				: "Continuing with the requester's answer"));
		Consumer<String> progress = update -> task.startWork(status(task, TaskState.WORKING, update));
		String request = conversation(context) + (payers.size() == 1 ? "\nPaying department: " + payers.get(0) : "");
		List<BudgetCheck> checks = new CopyOnWriteArrayList<>();
		try {
			String reply = this.chatClient.prompt()
				.user(request)
				.toolContext(Map.of(ProcurementTools.TASK_ID, task.getTaskId(), ProcurementTools.PROGRESS, progress,
						ProcurementTools.SEARCHED, ConcurrentHashMap.newKeySet(), ProcurementTools.CHECKS, checks))
				.call()
				.content();
			List<PurchaseRequest> raised = this.ledger.forTask(task.getTaskId());
			task.addArtifact(List.of(new TextPart(summary(clean(reply), raised, checks))), null, "summary", null);
			if (!raised.isEmpty()) {
				task.addArtifact(
						List.of(new DataPart(Map.of("purchaseRequests", raised.stream().map(PurchaseRequest::toData).toList()))),
						null, "purchase-request", null);
			}
			task.complete(status(task, TaskState.COMPLETED, raised.isEmpty() ? "Finished without raising a purchase request"
					: "Finished: raised " + raised.stream().map(PurchaseRequest::number).collect(Collectors.joining(", "))));
		}
		catch (RuntimeException ex) {
			log.warn("Procurement task {} failed", task.getTaskId(), ex);
			task.fail(status(task, TaskState.FAILED, "Could not process the request: " + ex.getMessage()));
		}
	}

	@Override
	public void cancel(RequestContext context, EventQueue eventQueue) throws JSONRPCError {
		if (context.getTask() == null || context.getTask().getStatus().state().isFinal()) {
			throw new TaskNotCancelableError();
		}
		TaskUpdater task = new TaskUpdater(context, eventQueue);
		task.cancel(status(task, TaskState.CANCELED, "Cancelled by the caller"));
	}

	/** An agent message for a status update; the metadata lets callers draw a timeline. */
	private static Message status(TaskUpdater task, TaskState state, String text) {
		return task.newAgentMessage(List.of(new TextPart(text)),
				Map.of("state", state.asString(), "at", Instant.now().toString()));
	}

	/**
	 * What the model sees. A new task is just the request; a task that was waiting for input
	 * replays the request, the question asked and the answer, from the A2A task history.
	 */
	static String conversation(RequestContext context) {
		StringBuilder prompt = new StringBuilder();
		for (Message message : messages(context)) {
			if (message.getRole() == Message.Role.USER) {
				prompt.append(prompt.isEmpty() ? "" : "The requester answered: ").append(text(message)).append('\n');
			}
			else if (message.getMetadata() != null
					&& TaskState.INPUT_REQUIRED.asString().equals(message.getMetadata().get("state"))) {
				prompt.append("You asked: ").append(text(message)).append('\n');
			}
		}
		return prompt.toString().strip();
	}

	/** Everything the caller has said in this task (not our own questions). */
	private static String callerText(RequestContext context) {
		return messages(context).stream()
			.filter(m -> m.getRole() == Message.Role.USER)
			.map(ProcurementAgentExecutor::text)
			.collect(Collectors.joining("\n"));
	}

	/** The task's messages in order: history, the current status message, then the new message. */
	private static List<Message> messages(RequestContext context) {
		List<Message> messages = new ArrayList<>();
		Task existing = context.getTask();
		if (existing != null && existing.getHistory() != null) {
			messages.addAll(existing.getHistory());
		}
		if (existing != null && existing.getStatus().message() != null) {
			messages.add(existing.getStatus().message());
		}
		messages.add(context.getMessage());
		Set<String> seen = new HashSet<>();
		return messages.stream().filter(m -> m != null && seen.add(m.getMessageId())).toList();
	}

	private static String text(Message message) {
		if (message == null || message.getParts() == null) {
			return "";
		}
		return message.getParts()
			.stream()
			.filter(TextPart.class::isInstance)
			.map(p -> ((TextPart) p).getText())
			.collect(Collectors.joining("\n"))
			.strip();
	}

	/**
	 * The text other agents read. Facts come from the ledger and the recorded budget checks, never
	 * from the model's prose: in live runs a 4B model misquoted totals and announced purchase
	 * requests it never raised. Its own words are kept only as a last resort, and only if they claim
	 * no purchase request.
	 */
	static String summary(String reply, List<PurchaseRequest> raised, List<BudgetCheck> checks) {
		if (!raised.isEmpty()) {
			return raised.stream()
				.map(pr -> "Raised %s: %d x %s (%s) for %d EUR, paid by %s. Status %s; expected delivery %s.".formatted(
						pr.number(), pr.quantity(), pr.item(), pr.sku(), pr.totalEur(), pr.department(), pr.status(),
						pr.expectedDelivery()))
				.collect(Collectors.joining("\n"));
		}
		BudgetCheck last = checks.isEmpty() ? null : checks.get(checks.size() - 1);
		if (last != null && last.remainingEur() == null) {
			return "No purchase request was raised: '%s' is not a known department.".formatted(last.department());
		}
		if (last != null && !last.covered()) {
			return "No purchase request was raised: the %s budget has %d EUR left this quarter, and %d x %s costs %d EUR."
				.formatted(last.department(), last.remainingEur(), last.quantity(), last.item(), last.totalEur());
		}
		return CLAIMS_A_PR.matcher(reply).find() || reply.isBlank() ? "No purchase request was raised." : reply;
	}

	/** Hybrid-thinking local models (Qwen3) can leave an empty think block in the answer. */
	private static String clean(String reply) {
		return reply == null ? "" : THINK_BLOCK.matcher(reply).replaceAll("").strip();
	}

}
