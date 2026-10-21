package com.acme.ai.rag;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.acme.ai.cache.SemanticCacheService;
import com.acme.ai.config.ApiExceptionHandler.NotFoundException;

/**
 * The "ETL" half of RAG: read (Tika handles PDF, DOCX, Markdown, HTML...), split into
 * token-sized chunks, enrich with metadata, embed and store in pgvector.
 * <p>
 * Each chunk carries {@code source}, {@code category} and {@code sha256} metadata. The hash
 * makes ingestion idempotent: re-uploading an unchanged file is a no-op, a changed file
 * replaces the old chunks.
 */
@Service
public class IngestionService {

	private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

	private final VectorStore vectorStore;

	private final JdbcClient jdbc;

	private final SemanticCacheService cache;

	// small chunks keep each retrieved excerpt focused on one rule; tune per corpus
	private final TokenTextSplitter splitter = TokenTextSplitter.builder()
		.withChunkSize(180)
		.withMinChunkSizeChars(100)
		.build();

	public IngestionService(VectorStore vectorStore, JdbcClient jdbc, SemanticCacheService cache) {
		this.vectorStore = vectorStore;
		this.jdbc = jdbc;
		this.cache = cache;
	}

	/**
	 * @param force re-chunk and re-embed even if the content is unchanged (e.g. after changing
	 * the splitter or the embedding model)
	 */
	@Transactional
	public IngestResult ingest(String source, Category category, byte[] content, boolean force) {
		String sha256 = sha256(content);
		var existing = this.jdbc.sql("SELECT sha256 FROM kb_document WHERE source = ?")
			.param(source)
			.query(String.class)
			.optional();
		if (!force && existing.isPresent() && existing.get().equals(sha256)) {
			log.info("Skipping {} - unchanged (sha256 {})", source, sha256.substring(0, 12));
			return new IngestResult(source, category, 0, true);
		}
		existing.ifPresent(old -> deleteChunks(source));

		Resource resource = new ByteArrayResource(content) {
			@Override
			public String getFilename() {
				return source;
			}
		};
		List<Document> chunks = this.splitter.apply(new TikaDocumentReader(resource).get());
		chunks.forEach(chunk -> {
			chunk.getMetadata().put("source", source);
			chunk.getMetadata().put("category", category.name());
			chunk.getMetadata().put("sha256", sha256);
		});
		this.vectorStore.add(chunks);

		this.jdbc.sql("""
				INSERT INTO kb_document (source, category, sha256, chunks) VALUES (:source, :category, :sha, :chunks)
				ON CONFLICT (source) DO UPDATE SET category = :category, sha256 = :sha, chunks = :chunks, ingested_at = now()
				""")
			.param("source", source)
			.param("category", category.name())
			.param("sha", sha256)
			.param("chunks", chunks.size())
			.update();
		log.info("Ingested {} ({}) as {} chunks", source, category, chunks.size());
		// cached answers may be based on the old content, so they must go
		this.cache.invalidate("rag");
		return new IngestResult(source, category, chunks.size(), false);
	}

	public IngestResult ingest(Resource resource, Category category, boolean force) throws IOException {
		try (InputStream in = resource.getInputStream()) {
			return ingest(resource.getFilename(), category, in.readAllBytes(), force);
		}
	}

	public List<KbDocument> list() {
		return this.jdbc.sql("SELECT id, source, category, chunks, ingested_at FROM kb_document ORDER BY source")
			.query(KbDocument.class)
			.list();
	}

	@Transactional
	public void delete(long id) {
		String source = this.jdbc.sql("SELECT source FROM kb_document WHERE id = ?")
			.param(id)
			.query(String.class)
			.optional()
			.orElseThrow(() -> new NotFoundException("No document with id " + id));
		deleteChunks(source);
		this.jdbc.sql("DELETE FROM kb_document WHERE id = ?").param(id).update();
		this.cache.invalidate("rag");
	}

	private void deleteChunks(String source) {
		this.vectorStore.delete(new FilterExpressionBuilder().eq("source", source).build());
	}

	private static String sha256(byte[] content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	public record IngestResult(String source, Category category, int chunks, boolean unchanged) {
	}

	public record KbDocument(long id, String source, String category, int chunks, java.time.LocalDateTime ingestedAt) {
	}

}
