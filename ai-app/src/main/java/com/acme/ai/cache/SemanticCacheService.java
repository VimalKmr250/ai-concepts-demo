package com.acme.ai.cache;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.document.DefaultContentFormatter;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.acme.ai.config.AcmeProperties;

/**
 * Demo 11 - semantic cache. Stores answered questions as vectors in their own pgvector table
 * ({@code semantic_cache}) and finds earlier answers to questions with the same meaning.
 * <p>
 * The store is built here instead of as a bean, so Spring Boot's auto-configured
 * {@code VectorStore} for the knowledge base stays untouched.
 */
@Service
public class SemanticCacheService {

	public static final String TABLE = "semantic_cache";

	private static final TypeReference<List<Map<String, Object>>> CITATIONS = new TypeReference<>() {
	};

	private final PgVectorStore store;

	private final JdbcClient jdbc;

	private final AcmeProperties.Cache config;

	private final String model;

	private final MeterRegistry meterRegistry;

	private final JsonMapper json = JsonMapper.builder().build();

	public SemanticCacheService(JdbcTemplate jdbcTemplate, JdbcClient jdbc, EmbeddingModel embeddingModel,
			AcmeProperties properties, MeterRegistry meterRegistry, ObservationRegistry observationRegistry,
			@Value("${spring.ai.vectorstore.pgvector.dimensions:768}") int dimensions,
			@Value("${spring.ai.openai.chat.model:unknown}") String model) {
		this.store = PgVectorStore.builder(jdbcTemplate, embeddingModel)
			.vectorTableName(TABLE)
			.dimensions(dimensions)
			.distanceType(PgDistanceType.COSINE_DISTANCE)
			.indexType(PgIndexType.HNSW)
			.initializeSchema(true)
			.observationRegistry(observationRegistry)
			.build();
		this.store.afterPropertiesSet();
		this.jdbc = jdbc;
		this.config = properties.cache();
		this.model = model;
		this.meterRegistry = meterRegistry;
		// register at 0 up front so Prometheus increase() also sees the first lookup
		counter("hit");
		counter("miss");
		tokensSaved();
	}

	public boolean enabled() {
		return this.config.enabled();
	}

	public double threshold() {
		return this.config.similarityThreshold();
	}

	/**
	 * Finds the closest earlier question in the same feature and scope (same model, not
	 * expired). Returned even below the threshold, so callers can show how close a miss was.
	 */
	public Optional<Match> closest(String feature, String scope, String question) {
		FilterExpressionBuilder f = new FilterExpressionBuilder();
		long cutoff = Instant.now().minus(this.config.ttl()).toEpochMilli();
		List<Document> found = this.store.similaritySearch(SearchRequest.builder()
			.query(question)
			.topK(1)
			.similarityThresholdAll()
			.filterExpression(f.and(f.and(f.eq("feature", feature), f.eq("scope", scope)),
					f.and(f.eq("model", this.model), f.gte("createdAt", cutoff)))
				.build())
			.build());
		if (found.isEmpty()) {
			return Optional.empty();
		}
		Document doc = found.get(0);
		Map<String, Object> md = doc.getMetadata();
		List<Map<String, Object>> citations = this.json.readValue(String.valueOf(md.getOrDefault("citations", "[]")),
				CITATIONS);
		return Optional.of(new Match(doc.getText(), String.valueOf(md.get("answer")), citations,
				doc.getScore() == null ? 0 : doc.getScore(), number(md.get("latencyMs")), number(md.get("tokens"))));
	}

	public void put(String feature, String scope, String question, String answer, List<Map<String, Object>> citations,
			long latencyMs, long tokens) {
		Map<String, Object> md = new HashMap<>();
		md.put("feature", feature);
		md.put("scope", scope);
		md.put("model", this.model);
		md.put("answer", answer);
		md.put("citations", this.json.writeValueAsString(citations));
		md.put("createdAt", Instant.now().toEpochMilli());
		md.put("latencyMs", latencyMs);
		md.put("tokens", tokens);
		Document entry = Document.builder().text(question).metadata(md).build();
		// embed only the question: by default (MetadataMode.EMBED) Spring AI embeds the metadata too,
		// which here would mix the answer and citations into the vector and ruin similarity
		entry.setContentFormatter(DefaultContentFormatter.builder()
			.withTextTemplate("{content}")
			.withExcludedEmbedMetadataKeys(new ArrayList<>(md.keySet()))
			.build());
		this.store.add(List.of(entry));
	}

	/** Drops every cached answer of a feature, e.g. after its source documents changed. */
	public void invalidate(String feature) {
		this.store.delete(new FilterExpressionBuilder().eq("feature", feature).build());
	}

	public int clear() {
		return this.jdbc.sql("DELETE FROM " + TABLE).update();
	}

	public List<Entry> entries() {
		return this.jdbc.sql("SELECT content, metadata::text AS md FROM " + TABLE + " ORDER BY (metadata->>'createdAt')::bigint DESC LIMIT 100")
			.query((rs, n) -> {
				Map<String, Object> md = this.json.readValue(rs.getString("md"), new TypeReference<Map<String, Object>>() {
				});
				return new Entry(rs.getString("content"), String.valueOf(md.get("feature")), String.valueOf(md.get("scope")),
						Instant.ofEpochMilli(number(md.get("createdAt"))).toString(), number(md.get("latencyMs")),
						number(md.get("tokens")));
			})
			.list();
	}

	public Stats stats() {
		double hits = counter("hit").count();
		double misses = counter("miss").count();
		double lookups = hits + misses;
		int entries = this.jdbc.sql("SELECT count(*) FROM " + TABLE).query(Integer.class).single();
		return new Stats((long) lookups, (long) hits, (long) misses, lookups == 0 ? 0 : hits / lookups,
				(long) tokensSaved().count(), entries, this.config.similarityThreshold());
	}

	Counter counter(String result) {
		return this.meterRegistry.counter("acme.cache.lookups", "feature", "rag", "result", result);
	}

	Counter tokensSaved() {
		return this.meterRegistry.counter("acme.cache.tokens.saved", "feature", "rag");
	}

	private static long number(Object value) {
		return value instanceof Number n ? n.longValue() : value == null ? 0 : (long) Double.parseDouble(value.toString());
	}

	/**
	 * @param question the cached question that matched
	 * @param similarity cosine similarity to the new question (1.0 = identical)
	 * @param latencyMs how long the original answer took
	 * @param tokens tokens the original answer used
	 */
	public record Match(String question, String answer, List<Map<String, Object>> citations, double similarity,
			long latencyMs, long tokens) {
	}

	public record Entry(String question, String feature, String scope, String createdAt, long latencyMs, long tokens) {
	}

	public record Stats(long lookups, long hits, long misses, double hitRate, long tokensSaved, int entries,
			double threshold) {
	}

	/** Shown with each RAG answer. */
	public record CacheInfo(boolean hit, Double similarity, String matchedQuestion, Long savedMs, Long savedTokens) {

		public static CacheInfo miss(Double closest, String closestQuestion) {
			return new CacheInfo(false, closest, closestQuestion, null, null);
		}

	}

	static List<Map<String, Object>> toCitations(List<Document> documents) {
		List<Map<String, Object>> result = new ArrayList<>();
		for (Document d : documents) {
			Map<String, Object> c = new HashMap<>();
			c.put("text", d.getText());
			c.put("source", d.getMetadata().get("source"));
			c.put("category", d.getMetadata().get("category"));
			c.put("score", d.getScore());
			result.add(c);
		}
		return result;
	}

	static List<Document> toDocuments(List<Map<String, Object>> citations) {
		return citations.stream().map(c -> {
			Map<String, Object> md = new HashMap<>();
			md.put("source", c.get("source"));
			md.put("category", c.get("category"));
			Object score = c.get("score");
			return Document.builder()
				.text(String.valueOf(c.get("text")))
				.metadata(md)
				.score(score instanceof Number n ? n.doubleValue() : null)
				.build();
		}).toList();
	}

}
