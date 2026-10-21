package com.acme.procurement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import reactor.core.publisher.Flux;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * A chat model that plays back queued answers (text or tool calls) and records the prompts.
 */
class ScriptedChatModel implements ChatModel {

	private final Deque<AssistantMessage> script = new ArrayDeque<>();

	private final List<Prompt> prompts = new ArrayList<>();

	synchronized ScriptedChatModel reply(String text) {
		this.script.add(new AssistantMessage(text));
		return this;
	}

	synchronized ScriptedChatModel callTool(String tool, String argumentsJson) {
		this.script.add(AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall("call-" + this.script.size(), "function", tool, argumentsJson)))
			.build());
		return this;
	}

	synchronized void reset() {
		this.script.clear();
		this.prompts.clear();
	}

	synchronized List<Prompt> prompts() {
		return List.copyOf(this.prompts);
	}

	@Override
	public synchronized ChatResponse call(Prompt prompt) {
		this.prompts.add(prompt);
		AssistantMessage next = this.script.isEmpty() ? new AssistantMessage("ok") : this.script.poll();
		return new ChatResponse(List.of(new Generation(next)));
	}

	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		return Flux.just(call(prompt));
	}

	@Override
	public ChatOptions getOptions() {
		return ToolCallingChatOptions.builder().build();
	}

}
