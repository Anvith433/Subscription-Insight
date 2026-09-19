package com.yourorg.subscription_backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Verifies the Spring context starts and every bean can be wired.
 *
 * <p>This needs a live MySQL: the context opens a real DataSource, runs Flyway, and
 * Hibernate validates every entity against the actual schema -- which is what makes
 * it worth running, since it catches an entity/column mismatch that no unit test
 * would. But it also means the test cannot pass on a machine without the database,
 * and {@code mvnw test} is what the Jenkinsfile runs, so the pipeline failed for a
 * missing database rather than a code fault.
 *
 * <p>It is therefore opt-in. Run it against the docker-compose database with:
 *
 * <pre>
 *   docker compose up -d
 *   INTEGRATION_DB=1 ./mvnw test
 * </pre>
 *
 * <p>The engine rules are covered by {@code RecommendationEngineTest}, which needs no
 * database and always runs.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "INTEGRATION_DB", matches = ".+",
        disabledReason = "Needs a MySQL instance; set INTEGRATION_DB=1 to run")
class SubscriptionBackendApplicationTests {

    @Test
    void contextLoads() {
    }
}
