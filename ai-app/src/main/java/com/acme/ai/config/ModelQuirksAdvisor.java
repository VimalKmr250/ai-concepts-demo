package com.acme.ai.config;

import java.util.List;
import java.util.regex.Pattern;

import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.Ordered;
import org.springframework.util.StringUtils;

/**
 * Adapts to quirks of small local models so feature code stays model-agnostic. It sits
 * innermost in the advisor chain (next to the model), so it sees every model round trip,
 * including tool-calling iterations:
 * <ul>
 * <li>appends a configurable suffix to the system prompt (e.g. Qwen3's {@code /no_think}
 * switch, which disables slow chain-of-thought output);</li>
 * <li>removes {@code <think>...</think>} reasoning blocks from answers, which would otherwise
 * leak into replies and break JSON parsing for structured output.</li>
 * </ul>
 * Both are configured under {@code acme.llm.*}; hosted providers need neither.
 */
public class ModelQuirksAdvisor implements CallAdvisor, StreamAdvisor {

	private static final Pattern THINK_BLOCK = Pattern.compile("(?s)<think>.*?</think>\\s*");

	private final String systemPromptSuffix;

	private final boolean stripThinking;

	public ModelQuirksAdvisor(String systemPromptSuffix, boolean stripThinking) {
		this.systemPromptSuffix = systemPromptSuffix;
		this.stripThinking = stripThinking;
	}

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
		ChatClientResponse response = chain.nextCall(augment(request));
		return this.stripThinking ? strip(response) : response;
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
		Flux<ChatClientResponse> responses = chain.nextStream(augment(request));
		if (!this.stripThinking) {
			return responses;
		}
		// drop streamed tokens between <think> and </think>
		boolean[] thinking = { false };
		return responses.map(r -> {
			String text = textOf(r);
			if (text == null) {
				return r;
			}
			StringBuilder visible = new StringBuilder();
			String rest = text;
			while (!rest.isEmpty()) {
				if (thinking[0]) {
					int end = rest.indexOf("</think>");
					if (end < 0) {
						rest = "";
					}
					else {
						thinking[0] = false;
						rest = rest.substring(end + "</think>".length()).stripLeading();
					}
				}
				else {
					int start = rest.indexOf("<think>");
					if (start < 0) {
						visible.append(rest);
						rest = "";
					}
					else {
						visible.append(rest, 0, start);
						thinking[0] = true;
						rest = rest.substring(start + "<think>".length());
					}
				}
			}
			return withText(r, visible.toString());
		});
	}

	private ChatClientRequest augment(ChatClientRequest request) {
		if (!StringUtils.hasText(this.systemPromptSuffix)) {
			return request;
		}
		SystemMessage system = request.prompt().getSystemMessage();
		String text = system.getText() == null ? "" : system.getText();
		if (text.contains(this.systemPromptSuffix)) {
			return request;
		}
		return request.mutate()
			.prompt(request.prompt().augmentSystemMessage(text + "\n" + this.systemPromptSuffix))
			.build();
	}

	private static ChatClientResponse strip(ChatClientResponse response) {
		String text = textOf(response);
		if (text == null || !text.contains("<think>")) {
			return response;
		}
		return withText(response, THINK_BLOCK.matcher(text).replaceAll("").strip());
	}

	private static String textOf(ChatClientResponse response) {
		ChatResponse chatResponse = response.chatResponse();
		if (chatResponse == null || chatResponse.getResult() == null) {
			return null;
		}
		return chatResponse.getResult().getOutput().getText();
	}

	private static ChatClientResponse withText(ChatClientResponse response, String text) {
		ChatResponse chatResponse = response.chatResponse();
		AssistantMessage replaced = chatResponse.getResult().getOutput().mutate().content(text).build();
		ChatResponse updated = ChatResponse.builder()
			.from(chatResponse)
			.generations(List.of(new Generation(replaced, chatResponse.getResult().getMetadata())))
			.build();
		return response.mutate().chatResponse(updated).build();
	}

	@Override
	public String getName() {
		return "ModelQuirksAdvisor";
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE - 100;
	}

}
