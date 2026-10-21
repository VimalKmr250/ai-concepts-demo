package com.acme.ai.cache;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;

import com.acme.ai.cache.SemanticCacheService.CacheInfo;
import com.acme.ai.cache.SemanticCacheService.Match;
import com.acme.ai.support.StubChatModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticCacheAdvisorTest {

	private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

	private final SemanticCacheService cache = mock(SemanticCacheService.class);

	private final StubChatModel model = new StubChatModel();

	private ChatClient client;

	@BeforeEach
	void setUp() {
		when(this.cache.enabled()).thenReturn(true);
		when(this.cache.threshold()).thenReturn(0.95);
		when(this.cache.counter(anyString()))
			.thenAnswer(inv -> this.meters.counter("acme.cache.lookups", "result", inv.getArgument(0, String.class)));
		when(this.cache.tokensSaved()).thenAnswer(inv -> this.meters.counter("acme.cache.tokens.saved"));
		this.client = ChatClient.builder(this.model).defaultAdvisors(new SemanticCacheAdvisor(this.cache, "rag")).build();
	}

	private ChatClientResponse ask(String question) {
		return this.client.prompt().user(question).advisors(a -> a.param(SemanticCacheAdvisor.SCOPE, "ALL")).call()
			.chatClientResponse();
	}

	@Test
	void hitAnswersFromTheCacheWithoutCallingTheModel() {
		when(this.cache.closest("rag", "ALL", "How many leave days can I carry forward?"))
			.thenReturn(Optional.of(new Match("How many leave days can I carry over?", "Up to 5 days [1].",
					List.of(Map.of("text", "carry over max 5 days", "source", "leave-policy.md", "category", "HR")), 0.97,
					4300, 512)));

		ChatClientResponse response = ask("How many leave days can I carry forward?");

		assertThat(response.chatResponse().getResult().getOutput().getText()).isEqualTo("Up to 5 days [1].");
		assertThat(this.model.prompts()).isEmpty();
		CacheInfo info = (CacheInfo) response.context().get(SemanticCacheAdvisor.CTX_CACHE);
		assertThat(info.hit()).isTrue();
		assertThat(info.savedTokens()).isEqualTo(512);
		@SuppressWarnings("unchecked")
		List<Document> docs = (List<Document>) response.context().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
		assertThat(docs).singleElement().satisfies(d -> assertThat(d.getMetadata()).containsEntry("source", "leave-policy.md"));
		assertThat(this.meters.counter("acme.cache.tokens.saved").count()).isEqualTo(512);
		verify(this.cache, never()).put(anyString(), anyString(), anyString(), anyString(), anyList(), anyLong(), anyLong());
	}

	@Test
	void closeButNotCloseEnoughCallsTheModelAndCachesTheAnswer() {
		when(this.cache.closest(eq("rag"), eq("ALL"), anyString()))
			.thenReturn(Optional.of(new Match("How many leave days can I carry over?", "Up to 5 days.", List.of(), 0.93, 0, 0)));
		this.model.reply("You get 26 days per year.");

		ChatClientResponse response = ask("How many leave days can I take next year?");

		assertThat(response.chatResponse().getResult().getOutput().getText()).isEqualTo("You get 26 days per year.");
		assertThat(this.model.prompts()).hasSize(1);
		CacheInfo info = (CacheInfo) response.context().get(SemanticCacheAdvisor.CTX_CACHE);
		assertThat(info.hit()).isFalse();
		assertThat(info.similarity()).isEqualTo(0.93);
		verify(this.cache).put(eq("rag"), eq("ALL"), eq("How many leave days can I take next year?"),
				eq("You get 26 days per year."), anyList(), anyLong(), anyLong());
	}

	@Test
	void bypassSkipsTheCacheEntirely() {
		this.model.reply("fresh answer");

		this.client.prompt().user("anything").advisors(a -> a.param(SemanticCacheAdvisor.BYPASS, true)).call().content();

		verify(this.cache, never()).closest(anyString(), anyString(), anyString());
		assertThat(this.model.prompts()).hasSize(1);
	}

}
