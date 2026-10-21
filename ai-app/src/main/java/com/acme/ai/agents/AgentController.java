package com.acme.ai.agents;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.acme.ai.agents.EvaluatorOptimizerWorkflow.EvaluatorResult;
import com.acme.ai.agents.OrchestratorWorkersWorkflow.OrchestratorResult;
import com.acme.ai.agents.ParallelizationWorkflow.ParallelResult;
import com.acme.ai.agents.RoutingWorkflow.RoutingResult;

/**
 * Demo 5 - the five workflow patterns from Anthropic's "Building effective agents",
 * implemented with plain Spring AI ChatClient calls.
 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

	private final ChainWorkflow chain;

	private final RoutingWorkflow routing;

	private final ParallelizationWorkflow parallelization;

	private final OrchestratorWorkersWorkflow orchestrator;

	private final EvaluatorOptimizerWorkflow evaluator;

	public AgentController(ChainWorkflow chain, RoutingWorkflow routing, ParallelizationWorkflow parallelization,
			OrchestratorWorkersWorkflow orchestrator, EvaluatorOptimizerWorkflow evaluator) {
		this.chain = chain;
		this.routing = routing;
		this.parallelization = parallelization;
		this.orchestrator = orchestrator;
		this.evaluator = evaluator;
	}

	@PostMapping("/chain")
	public List<AgentStep> chain(@Valid @RequestBody AgentInput input) {
		return this.chain.run(input.input());
	}

	@PostMapping("/routing")
	public RoutingResult routing(@Valid @RequestBody AgentInput input) {
		return this.routing.run(input.input());
	}

	@PostMapping("/parallel")
	public ParallelResult parallel(@Valid @RequestBody AgentInput input) throws Exception {
		return this.parallelization.run(input.input());
	}

	@PostMapping("/orchestrator")
	public OrchestratorResult orchestrator(@Valid @RequestBody AgentInput input) throws Exception {
		return this.orchestrator.run(input.input());
	}

	@PostMapping("/evaluator")
	public EvaluatorResult evaluator(@Valid @RequestBody AgentInput input) {
		return this.evaluator.run(input.input());
	}

	public record AgentInput(@NotBlank @Size(max = 6000) String input) {
	}

}
