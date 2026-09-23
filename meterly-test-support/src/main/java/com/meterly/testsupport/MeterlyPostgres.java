package com.meterly.testsupport;

import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A single PostgreSQL 16 container per test JVM, prepared exactly like production:
 * <ol>
 *   <li>the container user plays the schema-owner role and runs the Flyway migrations
 *       ({@code classpath:db/migration}; add {@code meterly-db} as a test dependency);</li>
 *   <li>a separate {@value #APP_ROLE} login role (NOSUPERUSER, NOBYPASSRLS, not the owner) is what the
 *       service under test connects as, so RLS policies are genuinely enforced in tests.</li>
 * </ol>
 *
 * <pre>{@code
 * @DynamicPropertySource
 * static void props(DynamicPropertyRegistry r) { MeterlyPostgres.registerDataSource(r); }
 * }</pre>
 */
public final class MeterlyPostgres {

    public static final String IMAGE = "postgres:16-alpine";
    public static final String APP_ROLE = "meterly_app";
    public static final String APP_PASSWORD = "meterly_app_test";

    private static final PostgreSQLContainer<?> CONTAINER =
            new PostgreSQLContainer<>(DockerImageName.parse(IMAGE))
                    .withDatabaseName("meterly")
                    .withUsername("meterly_owner")
                    .withPassword("meterly_owner_test");

    private static boolean prepared;

    private MeterlyPostgres() {
    }

    public static synchronized PostgreSQLContainer<?> start() {
        if (!prepared) {
            CONTAINER.start();
            createAppRole();
            migrate();
            prepared = true;
        }
        return CONTAINER;
    }

    /** Points the Spring DataSource at the container as the RLS-restricted application role. */
    public static void registerDataSource(DynamicPropertyRegistry registry) {
        start();
        registry.add("spring.datasource.url", CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
    }

    public static String jdbcUrl() {
        return start().getJdbcUrl();
    }

    /** Owner/superuser connection: bypasses RLS. Use only for fixtures, cleanup and assertions. */
    public static DataSource ownerDataSource() {
        start();
        return new DriverManagerDataSource(CONTAINER.getJdbcUrl(), CONTAINER.getUsername(), CONTAINER.getPassword());
    }

    /** Application-role connection: subject to RLS, exactly like the services. */
    public static DataSource appDataSource() {
        start();
        return new DriverManagerDataSource(CONTAINER.getJdbcUrl(), APP_ROLE, APP_PASSWORD);
    }

    public static JdbcTemplate ownerJdbc() {
        return new JdbcTemplate(ownerDataSource());
    }

    /** Removes all tenant data (keeps the plan catalog) so each test class starts clean. */
    public static void truncateTenantData() {
        // shedlock is deliberately NOT truncated: it is scheduler state owned by the running application.
        // ShedLock remembers that a lock row exists and afterwards only UPDATEs it; deleting the row under a
        // running service makes every later UPDATE match nothing, which ShedLock reads as "held elsewhere",
        // so that job would never run again in the test JVM.
        ownerJdbc().execute("""
                TRUNCATE tenant, app_user, refresh_token, subscription, metered_resource, api_key,
                         usage_event, usage_period_aggregate, invoice, invoice_line_item, notification,
                         outbox_event
                RESTART IDENTITY CASCADE
                """);
    }

    private static void createAppRole() {
        // Called from start() before "prepared" is set, so it must not go through ownerJdbc().
        new JdbcTemplate(new DriverManagerDataSource(
                CONTAINER.getJdbcUrl(), CONTAINER.getUsername(), CONTAINER.getPassword())).execute("""
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '%s') THEN
                        CREATE ROLE %s LOGIN PASSWORD '%s' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
                    END IF;
                END
                $$
                """.formatted(APP_ROLE, APP_ROLE, APP_PASSWORD));
    }

    private static void migrate() {
        MigrateResult result = Flyway.configure()
                .dataSource(CONTAINER.getJdbcUrl(), CONTAINER.getUsername(), CONTAINER.getPassword())
                .locations("classpath:db/migration")
                .placeholders(Map.of("appRole", APP_ROLE))
                .load()
                .migrate();
        if (result.migrationsExecuted == 0) {
            throw new IllegalStateException(
                    "No Flyway migrations found on the classpath; add com.meterly:meterly-db as a test dependency");
        }
    }
}
