package com.acme.ai.extraction;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.acme.ai.extraction.ExtractionService.ExtractionResult;

@RestController
@RequestMapping("/api/extract")
public class ExtractionController {

	private final ExtractionService extractionService;

	public ExtractionController(ExtractionService extractionService) {
		this.extractionService = extractionService;
	}

	@PostMapping("/invoice")
	public ExtractionResult<Invoice> invoice(@Valid @RequestBody ExtractionInput input) {
		return this.extractionService.extractInvoice(input.text());
	}

	@PostMapping("/meeting-notes")
	public ExtractionResult<MeetingNotes> meetingNotes(@Valid @RequestBody ExtractionInput input) {
		return this.extractionService.extractMeetingNotes(input.text());
	}

	public record ExtractionInput(@NotBlank @Size(max = 12000) String text) {
	}

}
