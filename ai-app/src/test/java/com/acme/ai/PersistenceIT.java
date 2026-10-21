package com.acme.ai;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.acme.ai.rag.HandbookLoader;
import com.acme.ai.rag.IngestionService;
import com.acme.ai.rag.IngestionService.IngestResult;
import com.acme.ai.support.HashingEmbeddingModel;
import com.acme.ai.support.PgVectorContainerConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full application context against a real Postgres + pgvector (Testcontainers). Embeddings
 * come from a deterministic fake, so no LLM is needed. Requires Docker.
 */
@SpringBootTest(properties = { "spring.ai.model.embedding=none", "acme.rag.ingest-on-startup=false" })
@Import({ PgVectorContainerConfig.class, PersistenceIT.FakeEmbeddings.class })
class PersistenceIT {

	@TestConfiguration(proxyBeanMethods = false)
	static class FakeEmbeddings {

		@Bean
		EmbeddingModel embeddingModel() {
			return new HashingEmbeddingModel();
		}

	}

	@Autowired
	HandbookLoader handbookLoader;

	@Autowired
	IngestionService ingestionService;

	@Autowired
	VectorStore vectorStore;

	@Autowired
	ChatMemoryRepository chatMemoryRepository;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void loadHandbook() throws Exception {
		this.handbookLoader.loadSamples(false);
	}

	@Test
	void flywayCreatesSchemaAndSeedData() {
		assertThat(this.jdbc.sql("SELECT count(*) FROM employee").query(Integer.class).single()).isEqualTo(8);
		assertThat(this.jdbc.sql("SELECT count(*) FROM ticket").query(Integer.class).single()).isGreaterThanOrEqualTo(6);
	}

	@Test
	void ingestionIsIdempotent() throws Exception {
		List<IngestResult> again = this.handbookLoader.loadSamples(false);

		assertThat(again).hasSize(6).allMatch(IngestResult::unchanged);
		assertThat(this.ingestionService.list()).hasSize(6);
	}

	@Test
	void retrievalHonoursCategoryFilter() {
		List<Document> results = this.vectorStore.similaritySearch(SearchRequest.builder()
			.query("report a lost or stolen laptop to the IT helpdesk")
			.topK(3)
			.filterExpression("category == 'IT'")
			.build());

		assertThat(results).isNotEmpty()
			.allSatisfy(d -> assertThat(d.getMetadata()).containsEntry("category", "IT")
				.containsEntry("source", "it-security-policy.md"));
	}

	@Test
	void pdfIsIngestedThroughTika() {
		List<Document> results = this.vectorStore.similaritySearch(SearchRequest.builder()
			.query("learning budget courses certifications conferences")
			.topK(1)
			.build());

		assertThat(results).singleElement().satisfies(d -> {
			assertThat(d.getMetadata()).containsEntry("source", "benefits-guide.pdf");
			assertThat(d.getText()).contains("1,500");
		});
	}

	@Test
	void deletingADocumentRemovesItsChunks() throws Exception {
		long id = this.ingestionService.list().stream().filter(d -> d.source().equals("code-of-conduct.md")).findFirst()
			.orElseThrow()
			.id();

		this.ingestionService.delete(id);

		List<Document> results = this.vectorStore.similaritySearch(SearchRequest.builder()
			.query("gifts hospitality speak up hotline")
			.topK(10)
			.filterExpression("source == 'code-of-conduct.md'")
			.build());
		assertThat(results).isEmpty();
	}

	@Test
	void chatMemoryIsPersistedInPostgres() {
		List<Message> turn = List.of(new UserMessage("I'm Priya, on the Pro plan"), new AssistantMessage("Hi Priya!"));

		this.chatMemoryRepository.saveAll("it-conversation", turn);

		assertThat(this.chatMemoryRepository.findByConversationId("it-conversation")).extracting(Message::getText)
			.containsExactly("I'm Priya, on the Pro plan", "Hi Priya!");
		assertThat(this.jdbc.sql("SELECT count(*) FROM spring_ai_chat_memory WHERE conversation_id = 'it-conversation'")
			.query(Integer.class)
			.single()).isEqualTo(2);
	}

}
