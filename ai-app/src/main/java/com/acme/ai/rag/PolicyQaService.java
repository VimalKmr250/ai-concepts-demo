package com.acme.ai.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import com.acme.ai.config.AcmeProperties;
import com.acme.ai.config.AiClients;

/**
 * Demo 3 - employee Q&A over the Acme handbook (retrieval-augmented generation).
 * <p>
 * Uses Spring AI's modular RAG: optional query rewrite → vector search (top-k, similarity
 * threshold, metadata filter) → prompt augmentation with numbered sources. When nothing
 * relevant is retrieved the augmenter makes the model say so instead of guessing.
 */
@Service
public class PolicyQaService {

	public static final String NOT_FOUND = "I couldn't find that in the Acme handbook documents.";

	private static final PromptTemplate ANSWER_TEMPLATE = new PromptTemplate("""
			You answer questions from Acme Corp employees using ONLY the handbook excerpts below.
			Each excerpt starts with a source number like [1].

			---------------------
			{context}
			---------------------

			Rules:
			1. Answer in at most 4 sentences, citing sources inline like [1] or [2].
			2. If the excerpts do not contain the answer, reply exactly: "%s"
			3. Do not use prior knowledge and do not mention "the excerpts" or "the context".

			Question: {query}
			Answer:
			""".formatted(NOT_FOUND));

	private static final PromptTemplate EMPTY_CONTEXT_TEMPLATE = new PromptTemplate("""
			Reply exactly with this sentence and nothing else: "%s"
			""".formatted(NOT_FOUND));

	private final ChatClient chatClient;

	private final AcmeProperties.Rag config;

	private final VectorStore vectorStore;

	private final List<QueryTransformer> queryTransformers = new ArrayList<>();

	public PolicyQaService(AiClients aiClients, VectorStore vectorStore, AcmeProperties properties) {
		this.config = properties.rag();
		this.vectorStore = vectorStore;
		if (this.config.rewriteQuery()) {
			this.queryTransformers
				.add(RewriteQueryTransformer.builder().chatClientBuilder(aiClients.forFeature("rag-rewrite")).build());
		}
		this.chatClient = aiClients.forFeature("rag").build();
	}

	public Answer ask(String question, Category category) {
		return ask(question, category, this.config.topK(), this.config.similarityThreshold()).answer();
	}

	/**
	 * Answers with explicit retrieval settings (used by the evaluation demo to show how bad
	 * settings degrade quality) and returns the retrieved documents for grading.
	 */
	public AnswerWithContext ask(String question, Category category, int topK, double similarityThreshold) {
		ChatClientResponse response = this.chatClient.prompt()
			.user(question)
			.advisors(ragAdvisor(topK, similarityThreshold))
			.advisors(a -> {
				if (category != null) {
					a.param(VectorStoreDocumentRetriever.FILTER_EXPRESSION, "category == '" + category.name() + "'");
				}
			})
			.call()
			.chatClientResponse();

		String answer = response.chatResponse() != null && response.chatResponse().getResult() != null
				? response.chatResponse().getResult().getOutput().getText() : NOT_FOUND;
		@SuppressWarnings("unchecked")
		List<Document> documents = (List<Document>) response.context()
			.getOrDefault(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT, List.of());
		List<Citation> citations = IntStream.range(0, documents.size())
			.mapToObj(i -> Citation.of(i + 1, documents.get(i), answer.contains("[" + (i + 1) + "]")))
			.toList();
		return new AnswerWithContext(new Answer(answer, citations), documents);
	}

	private RetrievalAugmentationAdvisor ragAdvisor(int topK, double similarityThreshold) {
		return RetrievalAugmentationAdvisor.builder()
			.queryTransformers(this.queryTransformers)
			.documentRetriever(VectorStoreDocumentRetriever.builder()
				.vectorStore(this.vectorStore)
				.topK(topK)
				.similarityThreshold(similarityThreshold)
				.build())
			.queryAugmenter(ContextualQueryAugmenter.builder()
				.promptTemplate(ANSWER_TEMPLATE)
				.emptyContextPromptTemplate(EMPTY_CONTEXT_TEMPLATE)
				.documentFormatter(PolicyQaService::numberedSources)
				.build())
			.build();
	}

	public record AnswerWithContext(Answer answer, List<Document> documents) {
	}

	private static String numberedSources(List<Document> documents) {
		return IntStream.range(0, documents.size())
			.mapToObj(i -> "[%d] (%s)%n%s".formatted(i + 1, documents.get(i).getMetadata().get("source"),
					documents.get(i).getText()))
			.collect(Collectors.joining("\n\n"));
	}

	public record Answer(String answer, List<Citation> citations) {
	}

	/**
	 * A retrieved chunk; {@code cited} is true when the answer references it as [number].
	 */
	public record Citation(int number, String source, String category, Double score, boolean cited,
			String snippet) {

		static Citation of(int number, Document doc, boolean cited) {
			String text = doc.getText() == null ? "" : doc.getText().strip();
			return new Citation(number, String.valueOf(doc.getMetadata().get("source")),
					String.valueOf(doc.getMetadata().get("category")), doc.getScore(), cited,
					text.length() > 400 ? text.substring(0, 400) + "…" : text);
		}

	}

}
