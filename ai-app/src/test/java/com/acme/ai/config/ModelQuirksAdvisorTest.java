package com.acme.ai.config;

import java.util.List;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import com.acme.ai.support.StubChatModel;

import static org.assertj.core.api.Assertions.assertThat;

class ModelQuirksAdvisorTest {

	@Test
	void appendsSystemSuffixAndStripsThinking() {
		StubChatModel model = new StubChatModel().reply("<think>\nlet me reason\n</think>\n\nThe answer is 42.");
		ChatClient client = ChatClient.builder(model)
			.defaultSystem("You are helpful.")
			.defaultAdvisors(new ModelQuirksAdvisor("/no_think", true))
			.build();

		String answer = client.prompt().user("question").call().content();

		assertThat(answer).isEqualTo("The answer is 42.");
		assertThat(model.lastPrompt().getSystemMessage().getText()).isEqualTo("You are helpful.\n/no_think");
	}

	@Test
	void stripsThinkingSpreadAcrossStreamedTokens() {
		// models emit <think> / </think> as single special tokens
		List<String> tokens = List.of("<think>", "secret", "</think>", "\n\nHel", "lo", " <think>more</think>world");
		StubChatModel model = new StubChatModel() {
			@Override
			public Flux<ChatResponse> stream(Prompt prompt) {
				return Flux.fromIterable(tokens)
					.map(t -> new ChatResponse(List.of(new Generation(new AssistantMessage(t)))));
			}
		};
		ChatClient client = ChatClient.builder(model).defaultAdvisors(new ModelQuirksAdvisor("", true)).build();

		String streamed = String.join("", client.prompt().user("hi").stream().content().collectList().block());

		assertThat(streamed).doesNotContain("secret", "more", "think");
		assertThat(streamed).endsWith("Hello world");
	}

	@Test
	void leavesResponsesAloneWhenDisabled() {
		StubChatModel model = new StubChatModel().reply("<think>x</think>visible");
		ChatClient client = ChatClient.builder(model).defaultAdvisors(new ModelQuirksAdvisor("", false)).build();

		assertThat(client.prompt().user("q").call().content()).isEqualTo("<think>x</think>visible");
	}

}
