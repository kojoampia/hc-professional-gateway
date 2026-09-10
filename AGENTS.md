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
- `config/dbmigrations/` — **the only "migration" mechanism**: `ApplicationRunner` `@Component`s using the blocking `MongoTemplate`, idempotent, no versioned-migration framework. Two of them, and they do opposite things: `InitialSetupMigration` only ever creates, `AngelAuthorityMigration` only ever removes.
- `config/dbmigrations/AngelAuthorityMigration.java` — strips the retired `ROLE_ANGEL` grant from every account that still carries it and deletes the `jhi_authority` row (`../docs/backlog.md` item 63, 2026-09-09). The counterpart item 44 gave `api/` and not this repo. It leaves the account activated and signable-in with an empty authority set, which reaches exactly what it reached before; deleting or deactivating an account from a boot-time runner was rejected, and the class says why at length. A no-op on any database without one, which is production and every test context.
- `config/dbmigrations/InitialSetupMigration.java` — an `ApplicationRunner` `@Component` that seeds `Authority` and default `User` documents at startup using the blocking `MongoTemplate`, idempotently (`saveUserIfMissing`). It now seeds **all eight clinical authorities** with a demo user each (doctor, nurse, carer, paramedic, pharmacist, therapist, chemist, technician) alongside `user`/`admin`. `ROLE_ANGEL` and the `angel` demo account were a ninth until 2026-09-08 and are gone — see below. Default passwords are derived from the login — change the admin password on any real deployment. There is no versioned-migration framework; schema is implicit in the documents.
- `broker/` — Kafka producer/consumer scaffold plus `RegistrationEventPublisher` (see § Domain events).
- `web/filter/` — reactive gateway filters.

## Authorities — a cross-repo invariant

`security/AuthoritiesConstants` declares the platform's authorities, including the **eight clinical disciplines**: `ROLE_DOCTOR`, `NURSE`, `PARAMEDIC`, `PHARMACIST`, `THERAPIST`, `CARER`, `CHEMIST`, `TECHNICIAN`.

The same set is duplicated in `api/security/AuthoritiesConstants` and in web's `config/authority.constants.ts` + `health-connect/authority-role.ts`, and it **drifts silently** — adding a role here without the other two repos produces a token whose role the microservice ignores and the UI can't badge. `api/` additionally enforces a mutation matrix (`CLINICAL_MUTATION`: admin, doctor, nurse, paramedic, pharmacist, therapist mutate; carer/chemist/technician are read-only in v1); this gateway does not, so authorising a role here is not the same as letting it write.

**`ROLE_ANGEL` was a ninth and is not an authority of this subsystem any more** (`../docs/backlog.md` item 44, 2026-09-08). A care angel supports one named patient; hc-patient holds the authority, an `ACTIVE CareDelegation` re-read per request, and the whole surface for it. Nothing here seeds it, names it or grants it, and since item 63 (2026-09-09) `config/dbmigrations/AngelAuthorityMigration` revokes it from any account that still holds it and deletes the authority document, so it is no longer assignable through `POST /api/admin/users` either. **A token carrying it still arrives regardless** — the three gateways share one signing key and this one stamps no `iss`, so hc-patient, which keeps the authority, goes on minting them — and it must go on meaning nothing: the `/services/**` rule is a positive list, so such a caller is exactly a role-less applicant. `AuthoritiesConstantsUnitTest` fails if the literal reappears in any privilege set in that class, and `ServicesRouteAuthorizationIT` sends a `ROLE_ANGEL` token at all three routes and requires 403.

This gateway is the **only** JWT issuer; downstream services validate. `../docs/professional-onboarding-workflow.md` (at the workspace root, since it spans all three repos) is the spec for the role model; Java comments in this repo cite it by bare filename.

## Domain events

`broker/RegistrationEventPublisher` publishes `registration.created` to `hc.professional.registration` via `StreamBridge`, for the admin portal — from **both** the self-service registration path and the administrator-created invitation path in `UserResource`. Envelope is `eventId`/`eventType`/`occurredAt`/`source`/`actor`/`payload`, keyed by `accountId`.

Two rules: **publishing must never break the registration path** (failures are logged, not propagated — keep the try/catch), and the payload carries identifiers plus `login`/`email`/`langKey` only. `api/` publishes `entity.created` and `compliance.alert` to a separate topic with the same envelope shape; keep the two in step. Covered by `RegistrationEventPublisherTest`.

## Application metrics

Two application meters, beside the JVM and HTTP ones the OpenTelemetry agent produces by itself. Both live in
`management/` and are read by a dashboard in `hc-professional-quality`, so **their names and tag keys are a published
interface** — `management/MeterScrapeNamesUnitTest` asserts the exported spellings literally for that reason
(`../docs/backlog.md` item 96).

| Micrometer name                  | Tag       | Values                              | Shape   | Emitted by                                           |
| -------------------------------- | --------- | ----------------------------------- | ------- | ---------------------------------------------------- |
| `security.authentication.logins` | `outcome` | `success`, `refused`, `unavailable` | counter | `AuthenticateController` on `POST /api/authenticate` |
| `security.registration.accounts` | `state`   | `activated`, `not-activated`        | gauge   | `service/RegistrationMetersRefresher`, every 60 s    |

Scraped as `security_authentication_logins_total{outcome="…"}` and `security_registration_accounts{state="…"}`.

Three things not to undo:

- **`refused` and `unavailable` are separate on purpose.** A credential the gateway asked about and was told no, and a
  user store the gateway could not ask at all, are different facts; one failure counter reports an outage as a wall of
  wrong passwords. Same argument as `../docs/backlog.md` item 83 at a different site.
- **The logins meter is not `security.authentication.invalid-tokens`.** That one counts tokens, and an `expired` token
  is a _successful_ login whose token has since aged out. Adding the two together reports a healthy user's return visit
  as an authentication failure. `SecurityMetersServiceTests` holds both directions of this.
- **The registration gauges read a field, never the database.** Micrometer samples a gauge synchronously on whatever
  thread is exporting or scraping, which here can be a Netty event loop; the query belongs on the scheduler, which is
  the only reason `RegistrationMetersRefresher` exists. A failed count publishes `NaN` — a gap on the panel — rather
  than zero or the previous value, both of which would assert a population nobody counted.

The refresh interval is a constant rather than a property: `ApplicationProperties` is `ignoreUnknownFields = false`, so
an `application.*` key with no matching binding fails context startup, and `src/test/resources/config/application.yml`
_replaces_ the production file on the test classpath — so no test in this repository would catch it
(`../docs/backlog.md` item 86, which is `api/`'s version of the same trap).

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

**There is no image build in this repo.** `build-image.sh` was deleted on 2026-09-06 (`../docs/backlog.md` item 34); it drove Jib against `docker-registry.jojoaddison.net`, a registry hostname that is not in use, and no image had been built from it since the deployment bundle was restructured in August. The production image is built from `../deploy/docker/gateway.Dockerfile` with this repo as the build context — by `../deploy/build.sh` on the `local` channel, and by this repo's own `.github/workflows/release.yml` on the `github` channel, which checks out `hc-professional-ci` for the Dockerfile. The pom's `jib-maven-plugin` configuration is left in place because the JHipster generator owns it; nothing in the deployment path calls it. If anyone ever revives it through `npm run java:docker`, the caveat that outlived the script is that `jib-maven-plugin.version` here is still `3.4.0` with no explicit `<mainClass>` in the jib `<container>` block — `api/` hit a wall on exactly that (Jib 3.4.1's bundled ASM cannot read Java 25 class files, major 69) and fixed it by moving to 3.4.6 and setting `<mainClass>${start-class}</mainClass>`.

### Build toolchain gotchas

The pom targets **release 25** and the enforcer's `requireJavaVersion` is `[25,27)`. Build with `JAVA_HOME=/usr/lib/jvm/jdk-25.0.2-oracle-x64` — the workstation's ambient `JAVA_HOME` points at `java-25-openjdk-amd64`, which is a JRE with no `javac`, and an incremental build hides that by finding nothing to compile. Verify a JDK claim with `clean verify`, never an incremental one. 26 stays in range on purpose: `../deploy/docker/gateway.Dockerfile` builds on `maven:3.9-eclipse-temurin-26`, so don't narrow the range without changing that Dockerfile in the same commit.

Deployment of the whole three-repo stack lives in `../deploy/` at the workspace root (runbook in its `README.md`), not here — it is its own git repository, `hc-professional-ci`. Note that the gateway and `api/` **must share one `JWT_BASE64_SECRET`** — their in-repo prod defaults differ, so a deployed stack never works until it is set; the deployed value lives in the untracked `../deploy/.env`.

## Testing

- JUnit 5. Integration tests (`*IT`) are annotated with the repo's `@IntegrationTest` and use **Testcontainers** for MongoDB and Kafka — Docker must be running for `./mvnw verify`.
- Reactive endpoints are tested with `WebTestClient`.

## Conventions

- Preserve JHipster generator needles (`// jhipster-needle-*`) — the generator uses them as insertion points.
- Prettier formats Java too (via the JHipster prettier plugin config in `package.json`/`.prettierrc`) — run `npm run prettier:format` after editing.
- Configuration lives in `src/main/resources/config/application*.yml`; Consul central config templates in `src/main/docker/central-server-config/`.
- `src/main/docker/` has compose files for consul, mongodb (single + cluster), kafka, monitoring (Prometheus/Grafana), zipkin, sonar; `jib/` for container builds.
