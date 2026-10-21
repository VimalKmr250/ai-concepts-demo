package com.acme.ai.rag;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import com.acme.ai.config.AcmeProperties;
import com.acme.ai.rag.IngestionService.IngestResult;

/**
 * Loads the sample Acme handbook (classpath:demo/handbook) into the knowledge base. Runs in the
 * background after startup so the app comes up even when the embedding model is not reachable;
 * the corpus can be (re)loaded later via {@code POST /api/docs/samples}.
 */
@Component
public class HandbookLoader {

	private static final Logger log = LoggerFactory.getLogger(HandbookLoader.class);

	private final IngestionService ingestionService;

	private final boolean ingestOnStartup;

	public HandbookLoader(IngestionService ingestionService, AcmeProperties properties) {
		this.ingestionService = ingestionService;
		this.ingestOnStartup = properties.rag().ingestOnStartup();
	}

	@EventListener(ApplicationReadyEvent.class)
	void loadOnStartup() {
		if (this.ingestOnStartup) {
			Thread.ofVirtual().name("handbook-loader").start(() -> {
				try {
					loadSamples(false);
				}
				catch (Exception ex) {
					log.warn("Sample handbook not ingested ({}). Start the embedding model and call POST /api/docs/samples.",
							ex.getMessage());
				}
			});
		}
	}

	public List<IngestResult> loadSamples(boolean force) throws IOException {
		Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath:demo/handbook/*");
		List<IngestResult> results = new ArrayList<>();
		for (Resource resource : resources) {
			results.add(this.ingestionService.ingest(resource, categoryOf(resource.getFilename()), force));
		}
		return results;
	}

	static Category categoryOf(String filename) {
		if (filename.startsWith("it-")) {
			return Category.IT;
		}
		if (filename.startsWith("travel-")) {
			return Category.FINANCE;
		}
		return Category.HR;
	}

}
