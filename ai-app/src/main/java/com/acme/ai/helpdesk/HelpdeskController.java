package com.acme.ai.helpdesk;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.acme.ai.helpdesk.ApprovalGatingToolCallingManager.ProposedCall;
import com.acme.ai.helpdesk.HelpdeskAgent.AgentResult;
import com.acme.ai.helpdesk.HelpdeskAgent.Status;
import com.acme.ai.helpdesk.HelpdeskRepository.AuditEntry;
import com.acme.ai.helpdesk.HelpdeskRepository.Ticket;

@RestController
public class HelpdeskController {

	private final HelpdeskAgent agent;

	private final HelpdeskRepository repository;

	public HelpdeskController(HelpdeskAgent agent, HelpdeskRepository repository) {
		this.agent = agent;
		this.repository = repository;
	}

	/** 200 when the agent finished, 202 when it is waiting for a human approval. */
	@PostMapping("/api/helpdesk/chat")
	public ResponseEntity<AgentResult> chat(@Valid @RequestBody HelpdeskRequest request) {
		return withStatus(this.agent.handle(request.message()));
	}

	@GetMapping("/api/approvals")
	public List<ApprovalView> approvals() {
		return this.agent.pending()
			.stream()
			.map(p -> new ApprovalView(p.id(), p.createdAt(), p.request(), p.proposedCalls()))
			.toList();
	}

	@PostMapping("/api/approvals/{id}/approve")
	public ResponseEntity<AgentResult> approve(@PathVariable String id,
			@RequestParam(defaultValue = "it-supervisor") String approver) {
		return withStatus(this.agent.approve(id, approver));
	}

	@PostMapping("/api/approvals/{id}/reject")
	public ResponseEntity<AgentResult> reject(@PathVariable String id,
			@RequestParam(defaultValue = "it-supervisor") String approver,
			@RequestParam(required = false) String reason) {
		return withStatus(this.agent.reject(id, approver, reason));
	}

	@GetMapping("/api/helpdesk/tickets")
	public List<Ticket> tickets(@RequestParam(required = false) String username) {
		return this.repository.tickets(username);
	}

	@GetMapping("/api/helpdesk/audit")
	public List<AuditEntry> audit() {
		return this.repository.auditLog();
	}

	private static ResponseEntity<AgentResult> withStatus(AgentResult result) {
		return ResponseEntity.status(result.status() == Status.PENDING_APPROVAL ? HttpStatus.ACCEPTED : HttpStatus.OK)
			.body(result);
	}

	public record HelpdeskRequest(@NotBlank @Size(max = 2000) String message) {
	}

	public record ApprovalView(String id, Instant createdAt, String request, List<ProposedCall> proposedCalls) {
	}

}
