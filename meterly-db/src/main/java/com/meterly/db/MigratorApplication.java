package com.meterly.db;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * One-shot schema migrator. Runs Flyway (via Spring Boot's Flyway auto-configuration) as the schema
 * owner role and exits with status 0 on success, non-zero on failure. Deployed as a Compose
 * dependency / Kubernetes Job that every service waits on, so services themselves never hold DDL
 * privileges and never race each other on startup.
 *
 * <p>Configuration (environment):
 * <pre>
 *   SPRING_FLYWAY_URL / SPRING_FLYWAY_USER / SPRING_FLYWAY_PASSWORD   owner connection
 *   SPRING_FLYWAY_PLACEHOLDERS_APPROLE                                 application role (default meterly_app)
 *   SPRING_FLYWAY_LOCATIONS                                            add classpath:db/demo for demo data
 * </pre>
 */
@SpringBootApplication
public class MigratorApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(MigratorApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        System.exit(SpringApplication.exit(app.run(args)));
    }
}
