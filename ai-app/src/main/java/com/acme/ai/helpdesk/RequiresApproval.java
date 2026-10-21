package com.acme.ai.helpdesk;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code @Tool} method as sensitive: the agent may <em>propose</em> calling it, but it
 * only runs after a human approves the call (human-in-the-loop).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresApproval {

	/** Why a human must look at this action; shown to the approver. */
	String value();

}
