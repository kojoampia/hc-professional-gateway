# AGENTS.md — hcProfessionalGateway

Guidance for AI agents working in this repository. Describes the code as it actually is.

## What this repository is

A JHipster 8.3.0–generated **reactive API gateway** for the Health Connect microservice architecture. It fronts the domain microservices (notably `professionalService` in the sibling `api/` repo) and owns **user management and JWT authentication** for the platform.

## Actual technology stack

- Java 25, Spring Boot 4.1 (`spring-boot-starter-parent` 4.1.0), Maven (`./mvnw`)
- **Reactive end-to-end**: Spring WebFlux + Spring Cloud Gateway (`spring-cloud-starter-gateway-server-webflux`). Use `Mono`/`Flux`; do not copy imperative Spring MVC patterns from the `api/` repo into this repo.
- **MongoDB** (`spring-boot-starter-data-mongodb-reactive`; the blocking driver is also on the classpath for the startup seeder). There is **no PostgreSQL, no JPA, no Liquibase, no Mongock** — ignore any doc that claims otherwise.
- Consul for service discovery and config (`spring-cloud-starter-consul-*`). **The app refuses to start if Consul is not reachable at `http://localhost:8500`.**
- JWT authentication (`security/jwt/`), issued by this gateway and validated by downstream services.
- Kafka via Spring Cloud Stream binder (`broker/KafkaConsumer`, `broker/KafkaProducer`).
- springdoc-openapi (WebFlux variant) for API docs.
- **No Lombok** — JHipster-style explicit getters/setters/builders.

Server port: **5505** (`application-dev.yml` / `application-prod.yml`). The Angular frontend (sibling `web/` repo) proxies to this port in dev.

## Code layout (`src/main/java/net/jojoaddison`)

- `domain/` — only `User`, `Authority`, `AbstractAuditingEntity`. Business entities live in the microservices, not here.
- `web/rest/` — account/user/auth endpoints (`AccountResource`, `AuthenticateController`, `UserResource`, `PublicUserResource`, `AuthorityResource`), `GatewayResource` (route introspection), Kafka test resource.
- `security/`, `security/jwt/` — Spring Security (reactive) + JWT token provider/validation.
- `service/`, `service/dto/`, `service/mapper/` — user service, DTOs, and mappers.
- `service/ProfileGateway.java` — Feign client targeting service id `hcprofessionalservice`; check the target service's actual Consul registration name before relying on it.
- `config/dbmigrations/InitialSetupMigration.java` — **the only "migration" mechanism**: an `ApplicationRunner` `@Component` that seeds `Authority` and default `User` documents at startup using the blocking `MongoTemplate`, idempotently (`saveUserIfMissing`). Default passwords are derived from the login. There is no versioned-migration framework; schema is implicit in the documents.
- `broker/` — Kafka producer/consumer.
- `web/filter/` — reactive gateway filters.

## Commands

```bash
npm run services:up        # start Consul + MongoDB + Kafka (docker compose -f src/main/docker/services.yml up --wait)
npm run docker:db:up       # MongoDB only
./mvnw                     # run dev profile (needs Consul + MongoDB)
./run-local.sh <args>      # wrapper: exports SPRING_MONGODB_URI from .env.local (copy .env.local.example), then runs ./mvnw
./mvnw verify              # full build + unit + integration tests
./mvnw test -Dtest=SomeTest          # single unit test
./mvnw verify -Dit.test=SomeResourceIT   # single integration test
./mvnw -Pprod clean verify # production jar → java -jar target/*.jar
./mvnw checkstyle:check    # style gate (checkstyle.xml, includes nohttp)
npm run lint / lint:fix    # ESLint (tooling/config files)
npm run prettier:check / prettier:format
```

## Testing

- JUnit 5. Integration tests (`*IT`) are annotated with the repo's `@IntegrationTest` and use **Testcontainers** for MongoDB and Kafka — Docker must be running for `./mvnw verify`.
- Reactive endpoints are tested with `WebTestClient`.

## Conventions

- Preserve JHipster generator needles (`// jhipster-needle-*`) — the generator uses them as insertion points.
- Prettier formats Java too (via the JHipster prettier plugin config in `package.json`/`.prettierrc`) — run `npm run prettier:format` after editing.
- Configuration lives in `src/main/resources/config/application*.yml`; Consul central config templates in `src/main/docker/central-server-config/`.
- `src/main/docker/` has compose files for consul, mongodb (single + cluster), kafka, monitoring (Prometheus/Grafana), zipkin, sonar; `jib/` for container builds.
