package com.acme.ai.guardrails;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Fast first-line heuristics for prompt-injection / jailbreak attempts. Cheap enough to run on
 * every request; an optional LLM classifier ({@link GuardrailAdvisors.PromptInjectionAdvisor})
 * can catch paraphrased attacks these patterns miss.
 */
public final class PromptInjectionDetector {

	private static final Map<String, Pattern> RULES = new LinkedHashMap<>();

	static {
		rule("override-instructions",
				"\\b(ignore|disregard|forget|override)\\b.{0,40}\\b(previous|prior|above|earlier|all|your|the)\\b.{0,20}\\b(instructions?|rules|prompts?|guidelines|directions)\\b");
		rule("reveal-system-prompt",
				"\\b(reveal|print|show|repeat|output|display|tell me|what (is|are))\\b.{0,30}\\b(system|initial|hidden|original|developer)\\s+(prompt|instructions?|message)\\b");
		rule("role-hijack",
				"\\b(you are now|from now on you are|act as|pretend (to be|you are))\\b.{0,40}\\b(dan|unrestricted|jailbroken|evil|without (any )?(rules|restrictions|filters))\\b");
		rule("jailbreak-keywords", "\\b(jailbreak|do anything now|developer mode|god mode)\\b");
		rule("fake-system-tags", "(</?\\s*system\\s*>|\\[\\s*system\\s*\\]|###\\s*system|<\\|im_start\\|>)");
	}

	private static void rule(String name, String regex) {
		RULES.put(name, Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL));
	}

	private PromptInjectionDetector() {
	}

	/**
	 * @return the name of the first rule that matched, or empty if the text looks safe
	 */
	public static Optional<String> detect(String text) {
		if (text == null) {
			return Optional.empty();
		}
		return RULES.entrySet().stream().filter(e -> e.getValue().matcher(text).find()).map(Map.Entry::getKey)
			.findFirst();
	}

}
