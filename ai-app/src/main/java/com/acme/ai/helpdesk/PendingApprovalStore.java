package com.acme.ai.helpdesk;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import com.acme.ai.helpdesk.ApprovalGatingToolCallingManager.ProposedCall;
import com.acme.ai.support.ToolTrace;

/**
 * Parks agent runs that are waiting for a human decision.
 * <p>
 * Demo shortcut: kept in memory, so pending approvals are lost on restart. A production system
 * would persist the conversation state (e.g. as JSON in a table) and notify approvers.
 */
@Component
public class PendingApprovalStore {

	private final Map<String, PendingApproval> pending = new ConcurrentHashMap<>();

	public PendingApproval park(String request, Prompt prompt, ChatResponse chatResponse, List<ProposedCall> calls,
			ToolTrace trace) {
		PendingApproval approval = new PendingApproval(UUID.randomUUID().toString(), Instant.now(), request, calls,
				prompt, chatResponse, trace);
		this.pending.put(approval.id(), approval);
		return approval;
	}

	/** Removes and returns the pending approval, so a decision can only be made once. */
	public Optional<PendingApproval> take(String id) {
		return Optional.ofNullable(this.pending.remove(id));
	}

	public List<PendingApproval> list() {
		return this.pending.values().stream().sorted(Comparator.comparing(PendingApproval::createdAt)).toList();
	}

	public record PendingApproval(String id, Instant createdAt, String request, List<ProposedCall> proposedCalls,
			Prompt prompt, ChatResponse chatResponse, ToolTrace trace) {
	}

}
