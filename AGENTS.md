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
- `config/dbmigrations/InitialSetupMigration.java` — **the only "migration" mechanism**: an `ApplicationRunner` `@Component` that seeds `Authority` and default `User` documents at startup using the blocking `MongoTemplate`, idempotently (`saveUserIfMissing`). It now seeds **all nine clinical authorities** with a demo user each (doctor, nurse, angel, carer, paramedic, pharmacist, therapist, chemist, technician) alongside `user`/`admin`. Default passwords are derived from the login — change the admin password on any real deployment. There is no versioned-migration framework; schema is implicit in the documents.
- `broker/` — Kafka producer/consumer scaffold plus `RegistrationEventPublisher` (see § Domain events).
- `web/filter/` — reactive gateway filters.

## Authorities — a cross-repo invariant

`security/AuthoritiesConstants` declares the platform's authorities, including the **nine clinical roles**: `ROLE_DOCTOR`, `NURSE`, `PARAMEDIC`, `PHARMACIST`, `THERAPIST`, `CARER`, `ANGEL`, `CHEMIST`, `TECHNICIAN`.

The same set is duplicated in `api/security/AuthoritiesConstants` and in web's `config/authority.constants.ts` + `health-connect/authority-role.ts`, and it **drifts silently** — adding a role here without the other two repos produces a token whose role the microservice ignores and the UI can't badge. `api/` additionally enforces a mutation matrix (`CLINICAL_MUTATION`: admin, doctor, nurse, paramedic, pharmacist, therapist mutate; carer/angel/chemist/technician are read-only in v1); this gateway does not, so authorising a role here is not the same as letting it write.

This gateway is the **only** JWT issuer; downstream services validate. `../docs/professional-onboarding-workflow.md` (at the workspace root, since it spans all three repos) is the spec for the role model; Java comments in this repo cite it by bare filename.

## Domain events

`broker/RegistrationEventPublisher` publishes `registration.created` to `hc.professional.registration` via `StreamBridge`, for the admin portal — from **both** the self-service registration path and the administrator-created invitation path in `UserResource`. Envelope is `eventId`/`eventType`/`occurredAt`/`source`/`actor`/`payload`, keyed by `accountId`.

Two rules: **publishing must never break the registration path** (failures are logged, not propagated — keep the try/catch), and the payload carries identifiers plus `login`/`email`/`langKey` only. `api/` publishes `entity.created` and `compliance.alert` to a separate topic with the same envelope shape; keep the two in step. Covered by `RegistrationEventPublisherTest`.

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
./build-image.sh [version] # WP8: Jib production image hc-professional-gateway; PUSH=1 to push to the registry
```

### Build toolchain gotchas

The pom targets **release 25**, but the build runs on **JDK 26** (`build-image.sh` pins `JAVA_HOME=/usr/lib/jvm/jdk-26-oracle-x64` when present).

**Untested caveat:** `jib-maven-plugin.version` here is still `3.4.0` with no explicit `<mainClass>` in the jib `<container>` block. The sibling `api/` repo hit a wall on exactly that — Jib 3.4.1's bundled ASM cannot read Java 25 class files (major 69) — and fixed it by moving to 3.4.6 and setting `<mainClass>${start-class}</mainClass>` in the jib container config. If `./build-image.sh` fails with an ASM/class-reading error, apply the same two changes; don't assume the version difference is deliberate.

Deployment of the whole three-repo stack lives in `../deploy/` at the workspace root (`docker-compose.professional.yml`, runbook in its `README.md`), not here. It invokes this repo's `build-image.sh` as `(cd ../gateway && ./build-image.sh <version>)`. Note that the gateway and `api/` **must share one `JWT_BASE64_SECRET`** — their in-repo prod defaults differ, so a deployed stack never works until it is set; the deployed value lives in the untracked `../deploy/.env`.

## Testing

- JUnit 5. Integration tests (`*IT`) are annotated with the repo's `@IntegrationTest` and use **Testcontainers** for MongoDB and Kafka — Docker must be running for `./mvnw verify`.
- Reactive endpoints are tested with `WebTestClient`.

## Conventions

- Preserve JHipster generator needles (`// jhipster-needle-*`) — the generator uses them as insertion points.
- Prettier formats Java too (via the JHipster prettier plugin config in `package.json`/`.prettierrc`) — run `npm run prettier:format` after editing.
- Configuration lives in `src/main/resources/config/application*.yml`; Consul central config templates in `src/main/docker/central-server-config/`.
- `src/main/docker/` has compose files for consul, mongodb (single + cluster), kafka, monitoring (Prometheus/Grafana), zipkin, sonar; `jib/` for container builds.
