package com.acme.ai.rag;

/**
 * Knowledge-base categories, stored as chunk metadata and used for retrieval filtering.
 * An enum (not free text) keeps user input out of the vector-store filter expression.
 */
public enum Category {

	HR, IT, FINANCE

}
