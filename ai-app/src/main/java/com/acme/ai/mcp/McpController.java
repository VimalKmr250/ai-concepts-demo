package com.acme.ai.mcp;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.acme.ai.mcp.TravelAssistantService.ServerCatalog;
import com.acme.ai.mcp.TravelAssistantService.TravelAnswer;

@RestController
@RequestMapping("/api/mcp")
public class McpController {

	private final TravelAssistantService travel;

	public McpController(TravelAssistantService travel) {
		this.travel = travel;
	}

	/** What the connected MCP server offers (discovered at runtime). */
	@GetMapping("/catalog")
	public ServerCatalog catalog() {
		return this.travel.catalog();
	}

	@GetMapping(path = "/resource", produces = MediaType.TEXT_PLAIN_VALUE)
	public String resource(@RequestParam(defaultValue = "travel://fx-rates") String uri) {
		return this.travel.readResource(uri);
	}

	@PostMapping("/ask")
	public TravelAnswer ask(@Valid @RequestBody TravelQuestion question) {
		return this.travel.ask(question.question());
	}

	@PostMapping("/trip-briefing")
	public TravelAnswer tripBriefing(@Valid @RequestBody TripRequest request) {
		return this.travel.tripBriefing(request.city(), request.date(), request.budget());
	}

	public record TravelQuestion(@NotBlank @Size(max = 2000) String question) {
	}

	public record TripRequest(@NotBlank String city, @NotBlank String date, String budget) {
	}

}
