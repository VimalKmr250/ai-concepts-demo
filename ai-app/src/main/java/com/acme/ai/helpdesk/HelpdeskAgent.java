package com.acme.ai.helpdesk;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.acme.ai.config.AiClients;
import com.acme.ai.config.ApiExceptionHandler.NotFoundException;
import com.acme.ai.helpdesk.ApprovalGatingToolCallingManager.ApprovalRequiredException;
import com.acme.ai.helpdesk.ApprovalGatingToolCallingManager.ProposedCall;
import com.acme.ai.helpdesk.PendingApprovalStore.PendingApproval;
import com.acme.ai.support.ToolTrace;

/**
 * Demo 4 - IT helpdesk agent: the model decides which business tools to call; sensitive tools
 * wait for a human (approve / reject) before they run.
 */
@Service
public class HelpdeskAgent {

	static final String SYSTEM_PROMPT = """
			You are the IT helpdesk agent of Acme Corp. Today is {today}.
			Use the tools to look up employees, their assets and tickets, and to act on requests.
			- Always look up the employee before acting for them.
			- When a device is broken, check its warranty and create a ticket for it.
			- If an employee cannot work because their laptop is broken, offer a loaner laptop.
			- Never claim that an action happened unless a tool result confirms it.
			- If a tool result says an action was REJECTED, tell the user it was not performed.
			Reply in at most 5 sentences and mention ticket numbers when you created tickets.
			""";

	private final ChatClient chatClient;

	private final HelpdeskTools tools;

	private final ToolCallingManager toolCallingManager;

	private final PendingApprovalStore approvals;

	private final HelpdeskRepository repository;

	private final MeterRegistry meterRegistry;

	private final Map<String, String> toolsRequiringApproval;

	public HelpdeskAgent(AiClients aiClients, HelpdeskTools tools, ToolCallingManager toolCallingManager,
			PendingApprovalStore approvals, HelpdeskRepository repository, MeterRegistry meterRegistry) {
		this.chatClient = aiClients.forFeature("helpdesk").defaultSystem(SYSTEM_PROMPT).build();
		this.tools = tools;
		this.toolCallingManager = toolCallingManager;
		this.approvals = approvals;
		this.repository = repository;
		this.meterRegistry = meterRegistry;
		this.toolsRequiringApproval = toolsRequiringApproval(HelpdeskTools.class);
		// register at 0 up front so Prometheus increase() also sees the first decision
		List.of("requested", "approved", "rejected").forEach(d -> meterRegistry.counter("acme.approvals", "decision", d));
	}

	/** Tool name to approval reason, read from {@link RequiresApproval} annotations. */
	static Map<String, String> toolsRequiringApproval(Class<?> toolsClass) {
		Map<String, String> result = new HashMap<>();
		for (Method method : toolsClass.getDeclaredMethods()) {
			RequiresApproval approval = method.getAnnotation(RequiresApproval.class);
			Tool tool = method.getAnnotation(Tool.class);
			if (approval != null && tool != null) {
				result.put(StringUtils.hasText(tool.name()) ? tool.name() : method.getName(), approval.value());
			}
		}
		return Map.copyOf(result);
	}

	public AgentResult handle(String message) {
		return run(message, List.of(new UserMessage(message)), new ToolTrace());
	}

	public AgentResult approve(String approvalId, String approver) {
		PendingApproval pending = take(approvalId);
		ToolExecutionResult result = ApprovalGatingToolCallingManager.executeAndTrace(this.toolCallingManager,
				pending.prompt(), pending.chatResponse(), pending.trace(), "approved");
		this.repository.audit(approver, "APPROVED", describe(pending.proposedCalls()));
		this.meterRegistry.counter("acme.approvals", "decision", "approved").increment();
		return run(pending.request(), result.conversationHistory(), pending.trace());
	}

	public AgentResult reject(String approvalId, String approver, String reason) {
		PendingApproval pending = take(approvalId);
		AssistantMessage proposal = pending.chatResponse().getResult().getOutput();
		String note = "REJECTED by a human approver" + (StringUtils.hasText(reason) ? ": " + reason : "")
				+ ". The action was NOT performed. Do not retry it.";
		List<ToolResponse> responses = proposal.getToolCalls()
			.stream()
			.map(call -> new ToolResponse(call.id(), call.name(), note))
			.toList();
		responses.forEach(r -> pending.trace().add(r.name(), argumentsOf(proposal, r.id()), note, "rejected"));

		List<Message> history = new ArrayList<>(pending.prompt().getInstructions());
		history.add(proposal);
		history.add(ToolResponseMessage.builder().responses(responses).build());
		this.repository.audit(approver, "REJECTED", describe(pending.proposedCalls()));
		this.meterRegistry.counter("acme.approvals", "decision", "rejected").increment();
		return run(pending.request(), history, pending.trace());
	}

	public List<PendingApproval> pending() {
		return this.approvals.list();
	}

	private AgentResult run(String request, List<Message> conversation, ToolTrace trace) {
		// the ChatClient adds the system prompt itself, so drop the copy kept in the history
		List<Message> messages = conversation.stream().filter(m -> !(m instanceof SystemMessage)).toList();
		var gate = new ApprovalGatingToolCallingManager(this.toolCallingManager, this.toolsRequiringApproval, trace);
		try {
			String reply = this.chatClient.prompt()
				.system(s -> s.param("today", java.time.LocalDate.now().toString()))
				.messages(messages)
				.tools(this.tools)
				.advisors(ToolCallingAdvisor.builder().toolCallingManager(gate).build())
				.call()
				.content();
			return AgentResult.completed(reply, trace.entries());
		}
		catch (ApprovalRequiredException ex) {
			PendingApproval pending = this.approvals.park(request, ex.getPrompt(), ex.getChatResponse(),
					ex.getProposedCalls(), trace);
			this.meterRegistry.counter("acme.approvals", "decision", "requested").increment();
			return AgentResult.pendingApproval(pending.id(), ex.getProposedCalls(), trace.entries());
		}
	}

	private PendingApproval take(String approvalId) {
		return this.approvals.take(approvalId)
			.orElseThrow(() -> new NotFoundException("No pending approval " + approvalId + " (already decided?)"));
	}

	private static String argumentsOf(AssistantMessage proposal, String toolCallId) {
		return proposal.getToolCalls()
			.stream()
			.filter(c -> c.id().equals(toolCallId))
			.map(AssistantMessage.ToolCall::arguments)
			.findFirst()
			.orElse("");
	}

	private static String describe(List<ProposedCall> calls) {
		return calls.stream().map(c -> c.tool() + c.arguments()).toList().toString();
	}

	public enum Status {

		COMPLETED, PENDING_APPROVAL

	}

	public record AgentResult(Status status, String reply, String approvalId, List<ProposedCall> proposedCalls,
			List<ToolTrace.Entry> toolTrace) {

		static AgentResult completed(String reply, List<ToolTrace.Entry> trace) {
			return new AgentResult(Status.COMPLETED, reply, null, List.of(), trace);
		}

		static AgentResult pendingApproval(String id, List<ProposedCall> calls, List<ToolTrace.Entry> trace) {
			return new AgentResult(Status.PENDING_APPROVAL, null, id, calls, trace);
		}

	}

}
