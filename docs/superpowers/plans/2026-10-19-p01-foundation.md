# P1 Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Repo skeleton, CI, a healthy local Docker stack (Caddy, app, MySQL 8.4, Kafka) and the common basics (ProblemDetail, AOP logging with request IDs, Flyway proven on MySQL 8.4), meeting milestone M1.

**Architecture:** One Spring Boot 4.1 app in `backend/` (package `com.offershield`, P1 creates only `common`). Only Caddy publishes a port (`127.0.0.1:80`) and routes `/api/*`, `/oauth2/*`, `/login/*` to `app:8080`; everything else returns 404 until the frontend arrives in P4.

**Tech Stack:** Java 21 · Spring Boot 4.1.1 (webmvc, data-jpa, flyway + flyway-mysql, aspectj, kafka, actuator, validation) · MySQL 8.4 · apache/kafka 4.3.1 (KRaft) · Caddy 2.11.7 · Maven Wrapper 3.3.4 → Maven 3.9.16 · JUnit 5, AssertJ, Mockito, Testcontainers 2.x.

**Spec:** `docs/plan/04-work-breakdown.md` P1-01…P1-03; milestone M1 in `docs/plan/03-implementation-roadmap.md` §2; decisions D-01, D-16, D-23, D-27, D-32 in `docs/plan/01-prd-review-and-open-decisions.md`.

## Global Constraints

- M-01 `backend/` + `frontend/` + root compose/Caddyfile; M-02 `com.offershield.<feature>`; M-03 Caddy at `http://localhost`. Builds use the Maven Wrapper: `backend/mvnw -B -f backend/pom.xml verify`.
- Only Caddy publishes a port: `127.0.0.1:80:80`. No `ports:` on app, mysql, kafka.
- Images: `mysql:8.4`, `apache/kafka:4.3.1`, `caddy:2.11.7-alpine`, `eclipse-temurin:21-jdk` (build), `eclipse-temurin:21-jre` (run, non-root). No Bitnami.
- Kafka: KRaft single broker, `auto.create.topics.enable=false`, retention 72 h.
- DB user `offershield_app` with rights on `offershield_db` only; root only in the init script.
- Logging (D-27): `class.method`, duration, outcome, error class, plus MDC `requestId`/`userId`/`submissionId`. Never arguments, return values, exception messages, letter text, emails, phones, UPI IDs or tokens. File `logs/offershield.log`, daily rolling, `maxHistory` 14.
- Actuator exposes `health` only. ProblemDetails (RFC 9457) for every error; 500 never leaks internals.
- Flyway `V{n}__desc.sql`, one DDL change per file. Tables: `BINARY(16)` UUIDs, InnoDB, `utf8mb4`.
- Secrets only in git-ignored `.env`; never printed or committed. `.env.example` holds names only.
- Shell scripts and `mvnw` keep LF endings (`.gitattributes`).

## Review Focus

1. An exception message carrying personal data (e.g. MySQL "Duplicate entry 'x@gmail.com'") written to the log by the 500 handler → `ProblemDetailExceptionHandlerTest.unexpectedError_logsClassOnly_noMessage` (Task 2).
2. A `requestId` leaking into the next request on a pooled thread → `RequestIdFilterTest.afterRequest_mdcCleared` (Task 3).
3. A client-supplied `X-Request-Id` used for log injection → `RequestIdFilterTest.incomingHeader_ignored_newIdGenerated` (Task 3).
4. Windows CRLF endings breaking `mvnw` or the MySQL init script inside Linux containers → `.gitattributes` (Task 1), proven by the stack start in Task 5.
5. The app starting before MySQL/Kafka are ready → `depends_on: condition: service_healthy` (Task 5), proven by `docker compose ps`.

---

### Task 1: Repository skeleton, wrapper, CI, Dependabot, local `.env` (P1-01)

**Files:**
- Create: `.gitignore`, `.gitattributes`, `.env.example`, `.env` (git-ignored, generated), `backend/pom.xml`, `backend/mvnw`, `backend/mvnw.cmd`, `backend/.mvn/wrapper/maven-wrapper.properties`, `backend/src/main/java/com/offershield/OfferShieldApplication.java`, `.github/workflows/ci.yml`, `.github/dependabot.yml`

**Interfaces:**
- Produces: Maven project `com.offershield:offershield:0.0.1-SNAPSHOT`, final jar `backend/target/offershield.jar`; `@SpringBootApplication class OfferShieldApplication`.

- [ ] **Step 1:** `.gitignore`: `.env`, `logs/`, `target/`, `node_modules/`, `dist/`, `.angular/`, IDE files. `.gitattributes`: `* text=auto eol=lf`, `*.cmd text eol=crlf`, `*.bat text eol=crlf`.
- [ ] **Step 2:** `.env.example` lists every 06 §2 variable (names only). Generate `.env` with the same names: `APP_BASE_URL=http://localhost`, `KAFKA_BOOTSTRAP=kafka:9092`, `MYSQL_ROOT_PASSWORD`/`DB_PASSWORD` (`openssl rand -base64 24`), `TEXT_AES_KEY_BASE64` (`-base64 32`), `INDICATOR_HMAC_PEPPER` (`-base64 48`) written straight to the file; provider credentials blank.
- [ ] **Step 3:** `pom.xml`: parent `spring-boot-starter-parent:4.1.1`, `java.version` 21; starters `webmvc`, `data-jpa`, `flyway` + `org.flywaydb:flyway-mysql`, `aspectj`, `kafka`, `actuator`, `validation`; `com.mysql:mysql-connector-j` (runtime); test: `spring-boot-starter-test`, `spring-boot-starter-webmvc-test`, `spring-boot-testcontainers`, `org.testcontainers:testcontainers-junit-jupiter`, `org.testcontainers:testcontainers-mysql`; `finalName` `offershield`; `maven-failsafe-plugin` with `integration-test` + `verify` goals.
- [ ] **Step 4:** Wrapper 3.3.4 only-script files; `distributionUrl` Maven 3.9.16 with `distributionSha256Sum=5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce`.
- [ ] **Step 5:** `ci.yml`: on push + pull_request, `permissions: contents: read`; job `backend` (checkout, setup-java temurin 21 with maven cache, `./backend/mvnw -B -f backend/pom.xml verify`); job `gitleaks` (checkout `fetch-depth: 0`, `gitleaks/gitleaks-action@v2`). `dependabot.yml`: weekly `maven` (`/backend`), `npm` (`/frontend`), `docker` (`/backend`), `github-actions` (`/`).
- [ ] **Step 6:** Run `backend/mvnw -B -f backend/pom.xml verify` → `BUILD SUCCESS`; `git check-ignore .env` → `.env`.
- [ ] **Step 7:** Commit `chore(p1-01): repo skeleton, Maven wrapper, CI, Dependabot`.

### Task 2: ProblemDetail exception handling (P1-03)

**Files:**
- Create: `backend/src/main/java/com/offershield/common/web/{ApiException,NotFoundException,ConflictException,UnprocessableException,ProblemDetailExceptionHandler}.java`
- Test: `backend/src/test/java/com/offershield/common/web/ProblemDetailExceptionHandlerTest.java`

**Interfaces:**
- Produces: `abstract class ApiException extends RuntimeException` with `ApiException(HttpStatus status, String errorCode, String detail)`, `HttpStatus status()`, `String errorCode()`; `NotFoundException(String detail)` → 404 `NOT_FOUND`; `ConflictException(String detail)` → 409 `CONFLICT`; `UnprocessableException(String detail)` → 422 `UNPROCESSABLE`. Response: `application/problem+json`, `type` `about:blank`, `title` = status reason phrase, `status`, `detail`, `instance` = request path, properties `errorCode`, `requestId` (MDC). 500: detail `"An unexpected error occurred."`, `errorCode` `INTERNAL`.

- [ ] **Step 1: Write the failing test** — `@WebMvcTest` with an imported test controller throwing each exception:
  - `notFound_returns404ProblemJson`: status 404, content type `application/problem+json`, `$.title` `"Not Found"`, `$.errorCode` `"NOT_FOUND"`, `$.detail` = thrown detail.
  - `conflict_returns409` (`CONFLICT`), `unprocessable_returns422` (`UNPROCESSABLE`).
  - `unexpectedError_returns500WithoutInternals`: thrown `IllegalStateException("SECRET-INTERNAL-MARKER")` → 500, `$.detail` `"An unexpected error occurred."`, body contains neither the marker nor `"IllegalStateException"` nor `"at com."`.
  - `unexpectedError_logsClassOnly_noMessage`: logback `ListAppender` on the handler logger; logged text contains `IllegalStateException`, not the marker.
  - `unknownPath_returns404Problem`: `GET /api/does-not-exist` → 404 `application/problem+json`.
- [ ] **Step 2:** Run `backend/mvnw -B -f backend/pom.xml test -Dtest=ProblemDetailExceptionHandlerTest` → compile failure.
- [ ] **Step 3:** Implement. Handler is `@RestControllerAdvice` extending `ResponseEntityExceptionHandler`; the 500 path logs `ERROR` with exception class and top stack frame only (no message, no throwable).
- [ ] **Step 4:** Re-run → all pass.
- [ ] **Step 5:** Commit `feat(p1-03): RFC 9457 ProblemDetail handler`.

### Task 3: Request ID, AOP logging aspect, logback file, ping endpoint (P1-03)

**Files:**
- Create: `backend/src/main/java/com/offershield/common/logging/{LogContext,RequestIdFilter,LoggingAspect}.java`, `backend/src/main/java/com/offershield/common/web/{PingController,PingResponse}.java`, `backend/src/main/resources/logback-spring.xml`
- Test: `backend/src/test/java/com/offershield/common/logging/{LoggingAspectTest,RequestIdFilterTest}.java`

**Interfaces:**
- Produces: `final class LogContext` with `REQUEST_ID = "requestId"`, `USER_ID = "userId"`, `SUBMISSION_ID = "submissionId"`, `REQUEST_ID_HEADER = "X-Request-Id"` (P2 sets `userId`, P5/P6 set `submissionId` in MDC). `RequestIdFilter extends OncePerRequestFilter`, highest precedence, always generates a UUID, sets MDC + response header, clears MDC in `finally`. `LoggingAspect` `@Around` on `within(com.offershield..*) && (within(@RestController *) || within(@Service *) || @annotation(KafkaListener))`; logs `call=<SimpleClass>.<method> durationMs=<n> outcome=ok` (INFO) or `outcome=error error=<ExceptionSimpleName>` (WARN) and rethrows. `GET /api/ping` → `200 {"status":"up"}`.

- [ ] **Step 1: Write the failing tests**
  - `LoggingAspectTest` (AspectJProxyFactory over a test `@Service` with `String echo(String)` and `void fail(String)`; `ListAppender` on `LoggingAspect`):
    - `logsMethodAndDuration`: message contains `call=SampleService.echo`, `durationMs=`, `outcome=ok`.
    - `neverLogsArguments`: called with `"ZEBRA-MARKER-ARG"`; no captured line contains it.
    - `neverLogsReturnValue`: `echo` returns `"RET-" + arg`; no line contains `RET-`.
    - `exception_loggedAsClassOnly_rethrown`: `fail("ZEBRA-MARKER-ERR")` throws `IllegalArgumentException("ZEBRA-MARKER-ERR")`; it is rethrown; line contains `outcome=error error=IllegalArgumentException`, not the marker.
  - `RequestIdFilterTest` (MockHttpServletRequest/Response, chain that captures `MDC.get("requestId")`):
    - `request_setsMdcAndResponseHeader`: captured id is a UUID and equals the `X-Request-Id` response header.
    - `incomingHeader_ignored_newIdGenerated`: request header `X-Request-Id: evil\nINJECT` → captured id ≠ that value and is a UUID.
    - `afterRequest_mdcCleared`: `MDC.get("requestId")` is null after `doFilter`.
- [ ] **Step 2:** Run both tests → compile failure.
- [ ] **Step 3:** Implement. `logback-spring.xml`: console + `RollingFileAppender` `${LOG_DIR:-logs}/offershield.log`, `TimeBasedRollingPolicy` `offershield.%d{yyyy-MM-dd}.log`, `maxHistory` 14, pattern `%d{ISO8601} %-5level [%thread] requestId=%X{requestId:--} userId=%X{userId:--} submissionId=%X{submissionId:--} %logger{36} - %msg%n`.
- [ ] **Step 4:** Re-run → all pass.
- [ ] **Step 5:** Commit `feat(p1-03): request ID filter, AOP logging aspect, file logging`.

### Task 4: Flyway baseline on MySQL 8.4 + application.yml (P1-03)

**Files:**
- Create: `backend/src/main/resources/application.yml`, `backend/src/main/resources/db/migration/V1__baseline.sql`, `backend/src/main/resources/db/migration/V2__create_processed_event.sql`
- Test: `backend/src/test/java/com/offershield/common/db/FlywayMigrationIT.java`

**Interfaces:**
- Produces: tables `audit_log(id BINARY(16) PK, actor_id BINARY(16) NOT NULL, action VARCHAR(64) NOT NULL, target_id BINARY(16) NULL, created_at DATETIME(6), updated_at DATETIME(6))` with indexes on `(actor_id, created_at)` and `target_id`; `processed_event(consumer VARCHAR(64), event_id VARCHAR(100), processed_at DATETIME(6) NOT NULL, PK(consumer, event_id))` with index on `processed_at`. Env vars read by the app: `DB_URL` (default `jdbc:mysql://mysql:3306/offershield_db`), `DB_USERNAME` (default `offershield_app`), `DB_PASSWORD` (no default), `KAFKA_BOOTSTRAP` (default `kafka:9092`), `LOG_DIR`.

- [ ] **Step 1: Write the failing test** `FlywayMigrationIT` (`@SpringBootTest`, `@Testcontainers`, `@Container @ServiceConnection static MySQLContainer mysql = new MySQLContainer("mysql:8.4")`):
  - `migrations_appliedOnMysql84_tablesExist`: `SELECT VERSION()` starts with `8.4`; `flyway_schema_history` has 2 rows with `success = 1` and versions `1`, `2`; `information_schema.tables` contains `audit_log` and `processed_event`.
  - `processedEvent_duplicateKey_rejected`: inserting the same `(consumer, event_id)` twice throws `DuplicateKeyException`.
- [ ] **Step 2:** Run `backend/mvnw -B -f backend/pom.xml verify -Dit.test=FlywayMigrationIT` → fails (no migrations/config).
- [ ] **Step 3:** Implement. `application.yml`: `spring.application.name=offershield`; datasource from the env vars above; `spring.jpa.open-in-view=false`, `hibernate.ddl-auto=validate`, `jdbc.time_zone=UTC`; `spring.flyway.enabled=true`; `spring.mvc.problemdetails.enabled=true`; `spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP:kafka:9092}`; `management.endpoints.web.exposure.include=health`, `management.endpoint.health.show-details=never`; `server.shutdown=graceful`.
- [ ] **Step 4:** Re-run → pass. Then the full `verify` → `BUILD SUCCESS`. If Flyway fails on 8.4: stop (D-32).
- [ ] **Step 5:** Commit `feat(p1-03): Flyway baseline (audit_log, processed_event) proven on MySQL 8.4`.

### Task 5: Docker Compose stack, Caddy, Dockerfile (P1-02)

**Files:**
- Create: `docker-compose.yml`, `Caddyfile`, `backend/Dockerfile`, `backend/.dockerignore`, `infra/mysql/init/01-app-user.sh`

**Interfaces:**
- Consumes: `backend/target`-free multi-stage build via `backend/mvnw`; `GET /actuator/health` and `GET /api/ping` (Task 3).
- Produces: services `caddy`, `app`, `mysql`, `kafka`; volumes `mysql-data`, `kafka-data`, `caddy-data`, `caddy-config`; bind mount `./logs:/app/logs`. Caddy site address `{$SITE_ADDRESS:http://localhost}` (prod sets `SITE_ADDRESS` in P14).

- [ ] **Step 1:** `01-app-user.sh` creates `offershield_db` (utf8mb4) and `offershield_app`@`%` with `SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES` on `offershield_db.*` only, via `mysql --protocol=socket -uroot`.
- [ ] **Step 2:** `Dockerfile`: stage `build` (`eclipse-temurin:21-jdk`, BuildKit cache for `/root/.m2`, `./mvnw -B -DskipTests package`); stage run (`eclipse-temurin:21-jre`, user `app` uid 10001, `/app/logs`, `java -XX:MaxRAMPercentage=60 -jar /app/offershield.jar`).
- [ ] **Step 3:** `docker-compose.yml`: healthchecks on all four services (`mysqladmin ping -h 127.0.0.1`, `kafka-broker-api-versions.sh`, app `/actuator/health` from inside its container, Caddy admin `:2019/config/`); `depends_on: condition: service_healthy`; only `caddy` has `ports: ["127.0.0.1:80:80"]`; `${VAR:?}` for required secrets.
- [ ] **Step 4:** `Caddyfile`: matcher `path /api/* /oauth2/* /login/*` → `reverse_proxy app:8080`; everything else `respond 404`.
- [ ] **Step 5: Verify** — `docker compose up -d --build`; `docker compose ps` → 4 × healthy; `docker compose port app 8080` → no output; `curl -s http://localhost/api/ping` → `{"status":"up"}`; `curl -s -o /dev/null -w "%{http_code}" http://localhost/actuator/health` → `404`; app log has Flyway `Successfully applied 2 migrations`; `grep requestId logs/offershield.log` shows a `call=PingController.ping durationMs=` line.
- [ ] **Step 6:** Commit `feat(p1-02): docker compose stack (Caddy, app, MySQL 8.4, Kafka KRaft)`.

### Task 6: Evidence and bookkeeping

**Files:**
- Modify: `docs/plan/05-testing-and-security-plan.md` §7, `docs/plan/04-work-breakdown.md` (tick P1), `docs/plan/01-prd-review-and-open-decisions.md` §3 (answers M-01…M-03 + wrapper) and §6 V-25, `CLAUDE.md` (Commands → wrapper)

- [ ] **Step 1:** Run every M1 verify command; record each with Executed and Passed separate.
- [ ] **Step 2:** Commit `docs(p1): M1 evidence, M-item answers, V-25 verified`.
