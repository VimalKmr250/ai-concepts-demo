package com.acme.ai.rag;

import java.io.IOException;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.acme.ai.rag.IngestionService.IngestResult;
import com.acme.ai.rag.IngestionService.KbDocument;
import com.acme.ai.rag.PolicyQaService.Answer;

@RestController
public class RagController {

	private final PolicyQaService qaService;

	private final IngestionService ingestionService;

	private final HandbookLoader handbookLoader;

	public RagController(PolicyQaService qaService, IngestionService ingestionService, HandbookLoader handbookLoader) {
		this.qaService = qaService;
		this.ingestionService = ingestionService;
		this.handbookLoader = handbookLoader;
	}

	@PostMapping("/api/rag/ask")
	public Answer ask(@Valid @RequestBody Question question) {
		return this.qaService.ask(question.question(), question.category());
	}

	@GetMapping("/api/docs")
	public List<KbDocument> documents() {
		return this.ingestionService.list();
	}

	@PostMapping("/api/docs")
	@ResponseStatus(HttpStatus.CREATED)
	public IngestResult upload(@RequestParam("file") MultipartFile file,
			@RequestParam(defaultValue = "HR") Category category) throws IOException {
		return this.ingestionService.ingest(file.getOriginalFilename(), category, file.getBytes(), false);
	}

	@PostMapping("/api/docs/samples")
	public List<IngestResult> loadSamples(@RequestParam(defaultValue = "false") boolean force) throws IOException {
		return this.handbookLoader.loadSamples(force);
	}

	@DeleteMapping("/api/docs/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable long id) {
		this.ingestionService.delete(id);
	}

	public record Question(@NotBlank @Size(max = 1000) String question, Category category) {
	}

}
