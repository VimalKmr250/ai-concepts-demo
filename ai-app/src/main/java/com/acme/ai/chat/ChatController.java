package com.acme.ai.chat;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.messages.Message;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

	private final SupportChatService chatService;

	public ChatController(SupportChatService chatService) {
		this.chatService = chatService;
	}

	@PostMapping
	public ChatReply chat(@Valid @RequestBody ChatRequest request) {
		return new ChatReply(request.conversationId(),
				this.chatService.reply(request.conversationId(), request.message()));
	}

	/**
	 * Token streaming over Server-Sent Events. Each event carries {@code {"t": "<token>"}};
	 * JSON-wrapping keeps leading spaces and newlines that raw SSE data lines would lose.
	 */
	@PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<Map<String, String>>> stream(@Valid @RequestBody ChatRequest request) {
		return this.chatService.streamReply(request.conversationId(), request.message())
			.map(token -> ServerSentEvent.builder(Map.of("t", token)).build())
			.concatWithValues(ServerSentEvent.<Map<String, String>>builder().event("done").data(Map.of()).build())
			.onErrorResume(ex -> Flux.just(ServerSentEvent.<Map<String, String>>builder()
				.event("error")
				.data(Map.of("message", String.valueOf(rootMessage(ex))))
				.build()));
	}

	@GetMapping("/{conversationId}/memory")
	public List<MemoryEntry> memory(@PathVariable String conversationId) {
		return this.chatService.memory(conversationId).stream().map(MemoryEntry::of).toList();
	}

	@DeleteMapping("/{conversationId}")
	public void forget(@PathVariable String conversationId) {
		this.chatService.forget(conversationId);
	}

	private static String rootMessage(Throwable ex) {
		Throwable t = ex;
		while (t.getCause() != null && t.getCause() != t) {
			t = t.getCause();
		}
		return t.getMessage();
	}

	public record ChatRequest(@NotBlank @Size(max = 36) String conversationId,
			@NotBlank @Size(max = 4000) String message) {
	}

	public record ChatReply(String conversationId, String reply) {
	}

	public record MemoryEntry(String type, String content) {

		static MemoryEntry of(Message m) {
			return new MemoryEntry(m.getMessageType().name(), m.getText() == null ? "" : m.getText());
		}

	}

}
