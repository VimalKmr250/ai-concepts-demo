package com.acme.ai.extraction;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Target type for "meeting transcript → summary, decisions and action items".
 */
public record MeetingNotes(@NotBlank @JsonPropertyDescription("Two-sentence summary of the meeting") String summary,
		@NotNull List<String> decisions, @NotNull List<@Valid ActionItem> actionItems) {

	public enum Priority {

		LOW, MEDIUM, HIGH

	}

	public record ActionItem(@NotBlank @JsonPropertyDescription("First name of the person responsible") String owner,
			@NotBlank @JsonPropertyDescription("What must be done, starting with a verb") String task,
			@JsonPropertyDescription("Due date ISO-8601 resolved against the meeting date, or null") LocalDate dueDate,
			@NotNull Priority priority) {
	}

}
