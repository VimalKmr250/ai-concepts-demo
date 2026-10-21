package com.acme.ai.cache;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.acme.ai.cache.SemanticCacheService.Entry;
import com.acme.ai.cache.SemanticCacheService.Stats;
import com.acme.ai.rag.PolicyQaService;

@RestController
@RequestMapping("/api/cache")
public class CacheController {

	private final SemanticCacheService cache;

	private final PolicyQaService qaService;

	public CacheController(SemanticCacheService cache, PolicyQaService qaService) {
		this.cache = cache;
		this.qaService = qaService;
	}

	@GetMapping("/stats")
	public Stats stats() {
		return this.cache.stats();
	}

	@GetMapping("/entries")
	public List<Entry> entries() {
		return this.cache.entries();
	}

	/**
	 * Looks a question up without asking the model and without storing anything, so a set of
	 * paraphrases can be compared against the same cached question.
	 */
	@PostMapping("/probe")
	public Probe probe(@Valid @RequestBody ProbeRequest request) {
		return this.cache.closest("rag", this.qaService.defaultCacheScope(null), request.question())
			.map(m -> new Probe(request.question(), m.question(), m.similarity(), m.similarity() >= this.cache.threshold(),
					this.cache.threshold()))
			.orElse(new Probe(request.question(), null, null, false, this.cache.threshold()));
	}

	@DeleteMapping
	public Map<String, Integer> clear() {
		return Map.of("removed", this.cache.clear());
	}

	public record ProbeRequest(@NotBlank @Size(max = 1000) String question) {
	}

	/**
	 * @param wouldHit whether a real request would be answered from the cache
	 */
	public record Probe(String question, String closestQuestion, Double similarity, boolean wouldHit,
			double threshold) {
	}

}
