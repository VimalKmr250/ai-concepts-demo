package com.acme.ai.evaluation;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.acme.ai.config.AcmeProperties;
import com.acme.ai.config.AiClients;
import com.acme.ai.rag.PolicyQaService;
import com.acme.ai.rag.PolicyQaService.AnswerWithContext;

/**
 * Demo 9 - RAG quality gate. Runs a golden question set through the real RAG pipeline and
 * grades every answer three ways:
 * <ul>
 * <li><b>expected facts</b> - deterministic keyword check against the known answer;</li>
 * <li><b>relevancy</b> - LLM-as-judge ({@link RelevancyEvaluator}): does the answer address
 * the question in line with the retrieved context?</li>
 * <li><b>groundedness</b> - LLM-as-judge ({@link FactCheckingEvaluator}): is the answer
 * supported by the retrieved documents (no hallucination)?</li>
 * </ul>
 * Used by the UI and by the {@code llm-eval} JUnit suite that fails the build on regressions.
 */
@Service
public class RagEvaluationService {

	private static final Logger log = LoggerFactory.getLogger(RagEvaluationService.class);

	private final PolicyQaService qaService;

	private final RelevancyEvaluator relevancy;

	private final FactCheckingEvaluator factChecking;

	private final AcmeProperties.Rag ragConfig;

	private final double minPassRate;

	private final List<GoldenCase> goldenSet;

	public RagEvaluationService(PolicyQaService qaService, AiClients aiClients, AcmeProperties properties)
			throws IOException {
		this.qaService = qaService;
		ChatClient.Builder judge = aiClients.forFeature("eval-judge")
			.defaultOptions(ChatOptions.builder().temperature(0.0))
			.defaultAdvisors(new VerdictNormalizerAdvisor());
		this.relevancy = RelevancyEvaluator.builder()
			.chatClientBuilder(judge)
			.promptTemplate(new PromptTemplate("""
					Does the response answer the query, and is it consistent with the context?
					The context may contain extra, unrelated passages - ignore them.
					Answer with exactly one word: YES or NO.

					Query:
					{query}

					Response:
					{response}

					Context:
					{context}
					"""))
			.build();
		this.factChecking = FactCheckingEvaluator.builder(judge)
			.evaluationPrompt("""
					Is the claim supported by the document? The claim counts as supported when the document
					states the same fact, even in different words (for example "no fewer than 3 visits"
					supports "the minimum is three visits"). Ignore details the claim does not mention.
					Answer with exactly one word: yes or no.

					Document:
					{document}

					Claim:
					{claim}
					""")
			.build();
		this.ragConfig = properties.rag();
		this.minPassRate = properties.evaluation().minPassRate();
		try (InputStream in = new ClassPathResource("eval/rag-golden.json").getInputStream()) {
			this.goldenSet = JsonMapper.builder()
				.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
				.build()
				.readValue(in, new TypeReference<List<GoldenCase>>() {
			});
		}
	}

	public List<GoldenCase> goldenSet() {
		return this.goldenSet;
	}

	public Scorecard run(Integer topK, Double similarityThreshold) {
		int k = topK != null ? topK : this.ragConfig.topK();
		double threshold = similarityThreshold != null ? similarityThreshold : this.ragConfig.similarityThreshold();
		List<CaseResult> results = new ArrayList<>();
		for (GoldenCase golden : this.goldenSet) {
			results.add(evaluateWithRetry(golden, k, threshold));
		}
		long passed = results.stream().filter(CaseResult::pass).count();
		double passRate = results.isEmpty() ? 0 : (double) passed / results.size();
		return new Scorecard(k, threshold, results, passed, results.size(), passRate, this.minPassRate,
				passRate >= this.minPassRate);
	}

	/**
	 * One retry absorbs transient model-server errors; a case that still errors counts as failed
	 * instead of aborting the whole scorecard.
	 */
	private CaseResult evaluateWithRetry(GoldenCase golden, int topK, double threshold) {
		RuntimeException last = null;
		for (int attempt = 1; attempt <= 2; attempt++) {
			try {
				return evaluate(golden, topK, threshold);
			}
			catch (RuntimeException ex) {
				last = ex;
				log.warn("Evaluation of '{}' failed (attempt {}): {}", golden.question(), attempt, ex.getMessage());
			}
		}
		return new CaseResult(golden.question(), "ERROR: " + last.getMessage(), false, null, null, null, false, 0);
	}

	private CaseResult evaluate(GoldenCase golden, int topK, double threshold) {
		long start = System.nanoTime();
		AnswerWithContext result = this.qaService.ask(golden.question(), null, topK, threshold);
		String answer = result.answer().answer();
		boolean notFound = answer.contains(PolicyQaService.NOT_FOUND);

		if (golden.expectNotFound()) {
			boolean pass = notFound;
			return new CaseResult(golden.question(), answer, pass, null, null, null, pass,
					(System.nanoTime() - start) / 1_000_000);
		}

		boolean factsPresent = !notFound && golden.mustContain()
			.stream()
			.allMatch(group -> group.stream()
				.anyMatch(alt -> answer.toLowerCase(Locale.ROOT).contains(alt.toLowerCase(Locale.ROOT))));
		Boolean relevant = null;
		Boolean grounded = null;
		Boolean citedCorrectly = null;
		if (!notFound && !result.documents().isEmpty()) {
			// judge the plain claim (no [n] markers / markdown) against everything that was retrieved
			String claim = plain(answer.replaceAll("\\s*\\[\\d+]", ""));
			List<Document> evidence = result.documents().stream().map(d -> new Document(plain(d.getText()))).toList();
			relevant = this.relevancy.evaluate(new EvaluationRequest(golden.question(), evidence, claim)).isPass();
			grounded = this.factChecking.evaluate(new EvaluationRequest(evidence, claim)).isPass();
			citedCorrectly = citesTheFact(answer, result.documents(), golden);
			if (!relevant || !grounded) {
				log.info("Judge rejected '{}' (relevant={}, grounded={})%nclaim: {}%nevidence:%n{}".replace("%n", "\n"),
						golden.question(), relevant, grounded, claim,
						evidence.stream().map(Document::getText).collect(Collectors.joining("\n---\n")));
			}
		}
		boolean pass = factsPresent && Boolean.TRUE.equals(relevant) && Boolean.TRUE.equals(grounded);
		return new CaseResult(golden.question(), answer, factsPresent, relevant, grounded, citedCorrectly, pass,
				(System.nanoTime() - start) / 1_000_000);
	}

	/**
	 * Deterministic citation check: does at least one chunk the answer cites as [n] contain the
	 * expected fact? Informational - small models often answer correctly but cite the wrong number.
	 */
	private static Boolean citesTheFact(String answer, List<Document> documents, GoldenCase golden) {
		if (golden.mustContain().isEmpty()) {
			return null;
		}
		List<String> firstFact = golden.mustContain().get(0);
		return IntStream.range(0, documents.size())
			.filter(i -> answer.contains("[" + (i + 1) + "]"))
			.mapToObj(i -> plain(documents.get(i).getText()).toLowerCase(Locale.ROOT))
			.anyMatch(text -> firstFact.stream().anyMatch(alt -> text.contains(alt.toLowerCase(Locale.ROOT))));
	}

	private static String plain(String text) {
		return text == null ? "" : text.replace("**", "").strip();
	}

	/**
	 * @param mustContain groups of alternatives; every group must match at least one alternative
	 * @param expectNotFound the correct behaviour is to say the handbook does not cover it
	 */
	public record GoldenCase(String question, List<List<String>> mustContain, boolean expectNotFound) {

		public GoldenCase {
			mustContain = mustContain == null ? List.of() : mustContain;
		}

	}

	/**
	 * @param citedCorrectly informational, not part of {@code pass}
	 */
	public record CaseResult(String question, String answer, boolean expectedFacts, Boolean relevant,
			Boolean grounded, Boolean citedCorrectly, boolean pass, long millis) {
	}

	public record Scorecard(int topK, double similarityThreshold, List<CaseResult> cases, long passed, int total,
			double passRate, double minPassRate, boolean gatePassed) {
	}

}
