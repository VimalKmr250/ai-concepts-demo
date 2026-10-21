package com.acme.ai.a2a;

import java.util.List;
import java.util.Map;

import io.a2a.spec.AgentCard;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.acme.ai.a2a.ProcurementAgentClient.AgentCallException;
import com.acme.ai.a2a.ProcurementAgentClient.Exchange;

@RestController
@RequestMapping("/api/a2a")
public class A2aController {

	private final ProcurementAgentClient client;

	public A2aController(ProcurementAgentClient client) {
		this.client = client;
	}

	@GetMapping("/card")
	public Map<String, Object> card() {
		AgentCard card = this.client.card();
		return Map.of("discoveredAt", this.client.baseUrl() + "/.well-known/agent-card.json", "card", card);
	}

	/** Talk to the procurement agent directly (the helpdesk agent uses the same client). */
	@PostMapping("/send")
	public Exchange send(@Valid @RequestBody SendRequest request) {
		return this.client.send(request.text(), request.taskId(), request.contextId(), "demo page");
	}

	@GetMapping("/exchanges")
	public List<Exchange> exchanges() {
		return this.client.recent();
	}

	@ExceptionHandler(AgentCallException.class)
	ProblemDetail agentError(AgentCallException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.getMessage());
		problem.setProperty("request", ex.request());
		problem.setProperty("response", ex.response());
		return problem;
	}

	/**
	 * @param taskId set to answer a task that is waiting in {@code input-required}
	 */
	public record SendRequest(@NotBlank @Size(max = 2000) String text, String taskId, String contextId) {
	}

}
