package com.acme.ai.evaluation;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.acme.ai.evaluation.RagEvaluationService.GoldenCase;
import com.acme.ai.evaluation.RagEvaluationService.Scorecard;

@RestController
@RequestMapping("/api/eval")
public class EvaluationController {

	private final RagEvaluationService evaluationService;

	public EvaluationController(RagEvaluationService evaluationService) {
		this.evaluationService = evaluationService;
	}

	@GetMapping("/golden-set")
	public List<GoldenCase> goldenSet() {
		return this.evaluationService.goldenSet();
	}

	/**
	 * Runs the quality gate. Override retrieval settings to demonstrate a regression, e.g.
	 * {@code ?topK=1&similarityThreshold=0.95}.
	 */
	@PostMapping("/rag")
	public Scorecard run(@RequestParam(required = false) Integer topK,
			@RequestParam(required = false) Double similarityThreshold) {
		return this.evaluationService.run(topK, similarityThreshold);
	}

}
