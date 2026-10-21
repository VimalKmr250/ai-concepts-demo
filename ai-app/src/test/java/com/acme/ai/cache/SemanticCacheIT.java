package com.acme.ai.cache;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.acme.ai.rag.Category;
import com.acme.ai.rag.IngestionService;
import com.acme.ai.support.PgVectorContainerConfig;
import com.acme.ai.support.StubModelsConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The semantic cache against a real pgvector table, with deterministic bag-of-words embeddings.
 */
@SpringBootTest(properties = { "spring.ai.model.chat=none", "spring.ai.model.embedding=none",
		"acme.rag.ingest-on-startup=false" })
@Import({ PgVectorContainerConfig.class, StubModelsConfig.class })
class SemanticCacheIT {

	@Autowired
	SemanticCacheService cache;

	@Autowired
	IngestionService ingestion;

	@BeforeEach
	void empty() {
		this.cache.clear();
	}

	private void store(String scope, String question) {
		this.cache.put("rag", scope, question, "answer to: " + question,
				List.of(Map.of("text", "chunk", "source", "leave-policy.md", "category", "HR")), 1200, 300);
	}

	@Test
	void identicalQuestionMatchesExactly() {
		store("ALL", "How many leave days can I carry over to next year?");

		var match = this.cache.closest("rag", "ALL", "How many leave days can I carry over to next year?");

		assertThat(match).hasValueSatisfying(m -> {
			assertThat(m.similarity()).isGreaterThan(0.999);
			assertThat(m.answer()).isEqualTo("answer to: How many leave days can I carry over to next year?");
			assertThat(m.citations()).singleElement().satisfies(c -> assertThat(c).containsEntry("source", "leave-policy.md"));
			assertThat(m.tokens()).isEqualTo(300);
		});
	}

	@Test
	void unrelatedQuestionIsFarAway() {
		store("ALL", "How many leave days can I carry over to next year?");

		var match = this.cache.closest("rag", "ALL", "Which laptop models does IT support?");

		// zero similarity is not returned at all; either way it is far below the threshold
		assertThat(match.map(SemanticCacheService.Match::similarity).orElse(0.0)).isLessThan(this.cache.threshold());
	}

	@Test
	void scopesAreSeparate() {
		store("HR|k=4|t=0.55", "How many leave days can I carry over to next year?");

		assertThat(this.cache.closest("rag", "IT|k=4|t=0.55", "How many leave days can I carry over to next year?"))
			.isEmpty();
	}

	@Test
	void ingestingADocumentClearsTheCache() throws Exception {
		store("ALL", "How many leave days can I carry over to next year?");
		assertThat(this.cache.stats().entries()).isEqualTo(1);

		this.ingestion.ingest("cache-test.md", Category.HR, "# New policy\n\nEveryone gets a day off.".getBytes(), true);

		assertThat(this.cache.stats().entries()).isZero();
	}

}
