package com.acme.ai.guardrails;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.acme.ai.guardrails.BankingAssistantService.GuardedReply;

@RestController
@RequestMapping("/api/guardrails")
public class GuardrailsController {

	private final BankingAssistantService assistant;

	public GuardrailsController(BankingAssistantService assistant) {
		this.assistant = assistant;
	}

	@PostMapping("/chat")
	public GuardedReply chat(@Valid @RequestBody GuardedChatRequest request) {
		return this.assistant.ask(request.conversationId(), request.message());
	}

	public record GuardedChatRequest(@NotBlank @Size(max = 36) String conversationId,
			@NotBlank @Size(max = 4000) String message) {
	}

}
