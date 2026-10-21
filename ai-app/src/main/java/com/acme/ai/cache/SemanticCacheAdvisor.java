package com.acme.ai.cache;

import java.util.List;
import java.util.Optional;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;

import com.acme.ai.cache.SemanticCacheService.CacheInfo;
import com.acme.ai.cache.SemanticCacheService.Match;

/**
 * Answers a question from the semantic cache when an earlier question meant the same thing,
 * skipping retrieval and the model entirely; otherwise lets the request through and caches the
 * answer. Sits in front of the RAG advisor (order -100 versus 0), behind the metrics advisor.
 */
public class SemanticCacheAdvisor implements CallAdvisor {

	/** Context key with the {@link CacheInfo} of this request. */
	public static final String CTX_CACHE = "acme.cache";

	/** Advisor param: cache scope (answers are only reused within the same scope). */
	public static final String SCOPE = "acme.cache.scope";

	/** Advisor param: skip the cache (the evaluation demo must measure the real pipeline). */
	public static final String BYPASS = "acme.cache.bypass";

	private final SemanticCacheService cache;

	private final String feature;

	public SemanticCacheAdvisor(SemanticCacheService cache, String feature) {
		this.cache = cache;
		this.feature = feature;
	}

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
		if (!this.cache.enabled() || Boolean.TRUE.equals(request.context().get(BYPASS))) {
			return chain.nextCall(request);
		}
		String question = request.prompt().getUserMessage().getText();
		String scope = String.valueOf(request.context().getOrDefault(SCOPE, "default"));
		Optional<Match> closest = this.cache.closest(this.feature, scope, question);

		if (closest.isPresent() && closest.get().similarity() >= this.cache.threshold()) {
			Match hit = closest.get();
			this.cache.counter("hit").increment();
			this.cache.tokensSaved().increment(hit.tokens());
			List<Document> documents = SemanticCacheService.toDocuments(hit.citations());
			ChatResponse cached = ChatResponse.builder()
				.generations(List.of(new Generation(new AssistantMessage(hit.answer()))))
				.build();
			return ChatClientResponse.builder()
				.chatResponse(cached)
				.context(request.context())
				.context(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT, documents)
				.context(CTX_CACHE, new CacheInfo(true, hit.similarity(), hit.question(), hit.latencyMs(), hit.tokens()))
				.build();
		}

		long start = System.nanoTime();
		ChatClientResponse response = chain.nextCall(request);
		long latencyMs = (System.nanoTime() - start) / 1_000_000;
		this.cache.counter("miss").increment();

		ChatResponse chatResponse = response.chatResponse();
		String answer = chatResponse == null || chatResponse.getResult() == null ? null
				: chatResponse.getResult().getOutput().getText();
		if (answer != null && !answer.isBlank()) {
			@SuppressWarnings("unchecked")
			List<Document> documents = (List<Document>) response.context()
				.getOrDefault(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT, List.of());
			Usage usage = chatResponse.getMetadata().getUsage();
			long tokens = usage == null || usage.getTotalTokens() == null ? 0 : usage.getTotalTokens();
			this.cache.put(this.feature, scope, question, answer, SemanticCacheService.toCitations(documents), latencyMs,
					tokens);
		}
		CacheInfo info = closest.map(m -> CacheInfo.miss(m.similarity(), m.question())).orElse(CacheInfo.miss(null, null));
		return response.mutate().context(CTX_CACHE, info).build();
	}

	@Override
	public String getName() {
		return "SemanticCacheAdvisor";
	}

	@Override
	public int getOrder() {
		return -100;
	}

}
