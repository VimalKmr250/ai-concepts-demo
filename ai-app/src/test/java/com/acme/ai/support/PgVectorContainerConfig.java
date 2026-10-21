package com.acme.ai.support;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;

/**
 * Postgres with the pgvector extension in a throwaway container; Spring Boot wires the
 * DataSource from it via {@code @ServiceConnection}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PgVectorContainerConfig {

	@Bean
	@ServiceConnection
	PostgreSQLContainer pgvector() {
		return new PostgreSQLContainer(
				DockerImageName.parse("pgvector/pgvector:0.8.7-pg17").asCompatibleSubstituteFor("postgres"));
	}

}
