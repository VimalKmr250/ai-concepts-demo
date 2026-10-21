package com.acme.ai.helpdesk;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

import com.acme.ai.observability.ExpectedOutcome;
import com.acme.ai.support.ToolTrace;

/**
 * Human-in-the-loop for tool calling.
 * <p>
 * In Spring AI 2.0 the tool-calling loop runs in the {@code ToolCallingAdvisor}, which hands
 * every model tool-call request to a {@link ToolCallingManager}. This decorator lets safe
 * tools execute immediately but stops the loop with {@link ApprovalRequiredException} when
 * the model asks for a tool annotated with {@link RequiresApproval}. The caller parks the
 * pending call and resumes the conversation once a human has decided.
 */
public class ApprovalGatingToolCallingManager implements ToolCallingManager {

	private final ToolCallingManager delegate;

	private final Map<String, String> toolsRequiringApproval;

	private final ToolTrace trace;

	/**
	 * @param toolsRequiringApproval tool name to the reason a human must approve it
	 */
	public ApprovalGatingToolCallingManager(ToolCallingManager delegate, Map<String, String> toolsRequiringApproval,
			ToolTrace trace) {
		this.delegate = delegate;
		this.toolsRequiringApproval = toolsRequiringApproval;
		this.trace = trace;
	}

	@Override
	public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
		return this.delegate.resolveToolDefinitions(chatOptions);
	}

	@Override
	public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
		List<ToolCall> calls = chatResponse.getResult().getOutput().getToolCalls();
		if (calls.stream().anyMatch(c -> this.toolsRequiringApproval.containsKey(c.name()))) {
			throw new ApprovalRequiredException(prompt, chatResponse, calls, this.toolsRequiringApproval);
		}
		return executeAndTrace(this.delegate, prompt, chatResponse, this.trace, "auto");
	}

	/**
	 * Executes the tool calls and records name, arguments and result of each in the trace.
	 */
	static ToolExecutionResult executeAndTrace(ToolCallingManager manager, Prompt prompt, ChatResponse chatResponse,
			ToolTrace trace, String mode) {
		ToolExecutionResult result = manager.executeToolCalls(prompt, chatResponse);
		List<Message> history = result.conversationHistory();
		Map<String, String> resultsById = history.isEmpty()
				|| !(history.get(history.size() - 1) instanceof ToolResponseMessage toolResponses) ? Map.of()
						: toolResponses.getResponses()
							.stream()
							.collect(Collectors.toMap(ToolResponse::id, ToolResponse::responseData, (a, b) -> a));
		chatResponse.getResult()
			.getOutput()
			.getToolCalls()
			.forEach(call -> trace.add(call.name(), call.arguments(), resultsById.getOrDefault(call.id(), ""), mode));
		return result;
	}

	/**
	 * Raised to pause the agent loop until a human approves or rejects the proposed calls.
	 */
	public static class ApprovalRequiredException extends RuntimeException implements ExpectedOutcome {

		private final transient Prompt prompt;

		private final transient ChatResponse chatResponse;

		private final transient List<ProposedCall> proposedCalls;

		ApprovalRequiredException(Prompt prompt, ChatResponse chatResponse, List<ToolCall> calls,
				Map<String, String> reasons) {
			super("Tool call requires human approval", null, false, false);
			this.prompt = prompt;
			this.chatResponse = chatResponse;
			this.proposedCalls = calls.stream()
				.map(c -> new ProposedCall(c.name(), c.arguments(), reasons.get(c.name())))
				.toList();
		}

		@Override
		public String outcome() {
			return "approval_pending";
		}

		public Prompt getPrompt() {
			return this.prompt;
		}

		public ChatResponse getChatResponse() {
			return this.chatResponse;
		}

		public List<ProposedCall> getProposedCalls() {
			return this.proposedCalls;
		}

	}

	/**
	 * A tool call the model wants to make.
	 * @param approvalReason why it needs approval, or {@code null} for a safe tool batched with it
	 */
	public record ProposedCall(String tool, String arguments, String approvalReason) {
	}

}
