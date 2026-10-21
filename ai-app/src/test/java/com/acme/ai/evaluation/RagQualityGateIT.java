package com.acme.ai.evaluation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.acme.ai.evaluation.RagEvaluationService.CaseResult;
import com.acme.ai.evaluation.RagEvaluationService.Scorecard;
import com.acme.ai.rag.HandbookLoader;
import com.acme.ai.support.PgVectorContainerConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demo 9 as a CI quality gate: runs the golden question set through the real RAG pipeline and
 * real models (LM Studio by default, or whatever provider is configured) and fails the build
 * when the pass rate drops below {@code acme.evaluation.min-pass-rate}.
 * <p>
 * Opt-in: {@code ./mvnw verify -Pllm-eval}. Regression demo:
 * {@code ./mvnw verify -Pllm-eval -Dacme.rag.top-k=1 -Dacme.rag.similarity-threshold=0.95}
 */
@Tag("llm-eval")
@SpringBootTest(properties = "acme.rag.ingest-on-startup=false")
@Import(PgVectorContainerConfig.class)
class RagQualityGateIT {

	@Autowired
	HandbookLoader handbookLoader;

	@Autowired
	RagEvaluationService evaluationService;

	@BeforeEach
	void ingestHandbook() throws Exception {
		this.handbookLoader.loadSamples(false);
	}

	@Test
	void ragAnswersMeetTheQualityBar() {
		Scorecard scorecard = this.evaluationService.run(null, null);

		printScorecard(scorecard);
		assertThat(scorecard.passRate())
			.as("RAG pass rate (top-k %d, threshold %.2f)", scorecard.topK(), scorecard.similarityThreshold())
			.isGreaterThanOrEqualTo(scorecard.minPassRate());
	}

	private static void printScorecard(Scorecard s) {
		System.out.printf("%n==== RAG quality gate: %d/%d passed (%.0f%%, minimum %.0f%%) - top-k %d, threshold %.2f ====%n",
				s.passed(), s.total(), s.passRate() * 100, s.minPassRate() * 100, s.topK(), s.similarityThreshold());
		System.out.printf("%-6s %-7s %-9s %-9s %-7s %s%n", "RESULT", "FACTS", "RELEVANT", "GROUNDED", "CITED", "QUESTION");
		for (CaseResult c : s.cases()) {
			System.out.printf("%-6s %-7s %-9s %-9s %-7s %s%n", c.pass() ? "PASS" : "FAIL", c.expectedFacts(),
					orNa(c.relevant()), orNa(c.grounded()), orNa(c.citedCorrectly()), c.question());
		}
		System.out.println();
	}

	private static String orNa(Boolean value) {
		return value == null ? "n/a" : value.toString();
	}

}
