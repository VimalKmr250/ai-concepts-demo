package com.acme.ai.support;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

import reactor.core.publisher.Flux;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * Scripted {@link ChatModel} for tests: returns queued answers in order (or a fallback) and
 * records every prompt it receives, so tests can assert what the model actually saw.
 */
public class StubChatModel implements ChatModel {

	private final Deque<Function<Prompt, AssistantMessage>> script = new ArrayDeque<>();

	private final List<Prompt> prompts = new ArrayList<>();

	private Function<Prompt, AssistantMessage> fallback = p -> new AssistantMessage("ok");

	public StubChatModel reply(String text) {
		this.script.add(p -> new AssistantMessage(text));
		return this;
	}

	public StubChatModel reply(Function<Prompt, AssistantMessage> answer) {
		this.script.add(answer);
		return this;
	}

	public StubChatModel replyToolCall(String id, String tool, String argumentsJson) {
		this.script.add(p -> AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", tool, argumentsJson)))
			.build());
		return this;
	}

	public StubChatModel otherwise(Function<Prompt, AssistantMessage> fallback) {
		this.fallback = fallback;
		return this;
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		Function<Prompt, AssistantMessage> next;
		synchronized (this) {
			this.prompts.add(prompt);
			next = this.script.isEmpty() ? this.fallback : this.script.poll();
		}
		// answer outside the lock so concurrent callers (parallel workflows) really run in parallel
		return new ChatResponse(List.of(new Generation(next.apply(prompt))));
	}

	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		return Flux.just(call(prompt));
	}

	/** Tool-calling options, so ChatClient attaches tools and runs its tool loop. */
	@Override
	public ChatOptions getOptions() {
		return ToolCallingChatOptions.builder().build();
	}

	public synchronized List<Prompt> prompts() {
		return List.copyOf(this.prompts);
	}

	public synchronized Prompt lastPrompt() {
		return this.prompts.get(this.prompts.size() - 1);
	}

}
