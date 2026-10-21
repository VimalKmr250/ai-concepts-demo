package com.acme.ai.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Deterministic bag-of-words embedding for integration tests: each word is hashed into one of
 * 768 dimensions and the vector is L2-normalised. Texts sharing words are similar, so vector
 * search behaves sensibly without calling a real embedding model.
 */
public class HashingEmbeddingModel implements EmbeddingModel {

	public static final int DIMENSIONS = 768;

	@Override
	public EmbeddingResponse call(EmbeddingRequest request) {
		List<Embedding> embeddings = new ArrayList<>();
		List<String> texts = request.getInstructions();
		for (int i = 0; i < texts.size(); i++) {
			embeddings.add(new Embedding(vector(texts.get(i)), i));
		}
		return new EmbeddingResponse(embeddings);
	}

	@Override
	public float[] embed(Document document) {
		return vector(document.getText());
	}

	@Override
	public int dimensions() {
		return DIMENSIONS;
	}

	static float[] vector(String text) {
		float[] v = new float[DIMENSIONS];
		for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
			if (word.length() > 2) {
				v[Math.floorMod(word.hashCode(), DIMENSIONS)] += 1f;
			}
		}
		double norm = 0;
		for (float x : v) {
			norm += x * x;
		}
		norm = Math.sqrt(norm);
		if (norm == 0) {
			v[0] = 1f;
			return v;
		}
		for (int i = 0; i < v.length; i++) {
			v[i] /= (float) norm;
		}
		return v;
	}

}
