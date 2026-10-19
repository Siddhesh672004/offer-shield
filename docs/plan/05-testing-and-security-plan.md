# 05 — Testing and Security Plan

**Principles:**
- Tests prove PRD claims and the approved decisions (D-29).
- Every kept §14 control (D-25) has a proof.
- **A test that was executed is not a test that passed.** Record both, with the command output (§7).
- **Fake letters only.** Never use real students' documents, phones or UPI IDs (PRD §18, §19.5).

## 1. Strategy

| Layer | Tools | Used for |
|---|---|---|
| Unit | JUnit 5, AssertJ, Mockito (`method_condition_expected` names) | Checks, scoring, SimHash, HMAC, cipher, validators, LLM chain, masking, AOP logger |
| Web slice | `@WebMvcTest` + `@MockitoBean` + Spring Security test | 401/403/404, ProblemDetail, CSRF, headers, rate limit |
| DB integration | MySQL 8.4 Testcontainers (Docker must run); **no H2** | Owner-scoped queries, idempotency race, `BIT_COUNT` matching |
| Kafka | Listener unit tests with mocked `KafkaTemplate`; optional `@EmbeddedKafka` for DLT routing | Dedupe, re-send fix, DLT, sweeper |
| Frontend | Angular 22 default runner (Vitest), `HttpTestingController`, fake timers | Guards, interceptor, polling, idempotency key reuse, trust labels |
| Manual | Journeys J1–J6 (§5), proofs S-01…S-12 (§6), drills F-01…F-06 (§3) | PRD "done when" conditions |

## 2. Mandatory tests

### 2.1 PRD §18 (must pass)

| Test | Task | Must assert |
|---|---|---|
| `ScoringEngineTest` | P6-06 | Hard overrides: C1, C10, C11 non-official → High. **Only** these three can override. Unverified (C12) alone never overrides. Band edges 24/25/49/50. Total capped at 100 |
| `DomainValidatorTest` (+ `AnalysisServiceTest.ssrfSenders_noLookupCalls`) | P6-03, P6-07 | `127.0.0.1`, `[::1]`, `169.254.169.254`, `evil.com:8080/x`, `http://x` (and decimal/hex IPs, `localhost`) are rejected, with **zero** RDAP/DNS calls |
| `SubmissionAccessTest` | P5-01 | User A → 404 on B's submission; own → 200 (Testcontainers) |
| `LlmChainTest` | P7-01 | 429 → next model; `retry-after`/duration headers honoured; timeout and connection refused → next; all fail → empty |
| `PromptInjectionTest` | P7-02 | Hostile **valid** AI output: the band is unchanged, `score(on) ≥ score(off)`, and the checks triggered with AI on include all of those with AI off (R-A) |
| `SimHashTest` | P9-02 | Near-duplicates ≤ 3; different texts > 3; changing only names/numbers/dates → 0–1; < 50 words → none; genuine letters sharing boilerplate > 3 |
| `CopiedLetterGuardTest` | P9-04 | Official sender → +20, not High; non-official → High; AI-only company → no downgrade |

### 2.2 Security tests (D-25, PRD §14)

| Test | Task | Proves |
|---|---|---|
| `ReportAccessTest`, `ScamReportAccessTest`, `PdfLinkAccessTest` | P6-07, P10-01, P10-04 | §14.3 IDOR: 404 for others' report, submission and PDF link. Includes a positive control and the same 404 body for foreign and random IDs |
| `OidcUserProvisioningServiceTest`, `LoginRedirectTest`, `SessionCookieTest`, `ApiUnauthenticatedTest` | P2-02 | §14.1: users keyed on `sub`; `email_verified` rule (R-C); state/nonce/PKCE; cookie flags; 401 not redirect |
| `CsrfTest`, `SecurityHeadersTest`, `UserRateLimitFilterTest` | P2-03 | §14.2 CSRF, §14.7 headers (D-10 CSP), D-12 limit |
| `FileValidatorTest`, `SubmissionServiceTest.invalidPdf_cloudinaryNeverCalled` | P5-02, P5-04 | §14.4 size/type/pages/encrypted/timeout/image-only; upload only after validation |
| `TextCipherTest`, `IndicatorHasherTest` | P5-03, P9-02 | §14.6 encryption, HMAC |
| `RdapClientTest` | P6-04 | §14.5 allow-list, no redirect off-list |
| `RedactorTest`, `ProviderRequestRedactionTest` | P7-02 | §8.5 + D-07: no raw PII in the request sent to Groq |
| `LoggingAspectTest.neverLogsArguments` | P1-03 | §14.6 + D-27: no PII in logs |

### 2.3 Rule and reliability tests (D-29)

| Test | Task | Proves |
|---|---|---|
| `MoneyRequestCheckTest` (`negatedFeeMention_notTriggered`, `negatorOnOtherNoun_triggered`) | P6-02 | D-09 |
| `IndicatorFactoryTest.freeMailDomain_neverBecomesIndicator`, `MatchServiceIT` | P9-03 | D-08, D-33.5, `BIT_COUNT` |
| `NearDuplicateCheckTest`, `ConfirmedIndicatorCheckTest` (`unverifiedMatch_noOverride`) | P9-04 | R-B |
| `LookalikeDomainCheckTest.inf0sysCareers_flagged` | P8-02 | D-03 + PRD done-when |
| `MembershipDecisionTest`, `ReviewDecisionConcurrencyTest`, `ReviewScopeTest`, `ReviewDecisionTest`, `ScamReportDecisionTest`, `RevertTest`, `CollegeAdminControllerTest` | P3, P10 | 409 on stale `If-Match`; college scope; audit rows (R-E); D-13 |
| `IdempotencyKeyTest` (incl. the concurrent double-click), `UploadLimitTest` | P5-01 | D-33.2, D-12 |
| `SubmissionEventPublisherTest`, `QueuedSubmissionSweeperTest` | P5-05 | D-28 publish after commit, backoff, FAILED cap |
| `SubmissionCreatedListenerTest` (incl. `duplicateWithExistingReport_resendsCompletion`), `SubmissionStatusListenerTest`, `ReportSnapshotListenerTest` | P6, P9 | Dedupe, re-send fix, DLT |
| `RetentionJobTest` | P11-01 | D-06 |
| `EmailServiceTest.smtpFailure_loggedDecisionKept` | P3-02 | D-22 |
| Frontend: `auth.guard`, `role.guard`, `unauthorized.interceptor`, `join-college`, `upload` (key reuse), `report` (stops on READY/PARTIAL/FAILED, waits for previous, trust labels distinct) | P4, P12 | §12, D-14 |

**Cuttable only with their feature (03 §4):** `ExplainServiceTest` (cut #1).

## 3. Failure drills (P13, local stack)

| ID | Scenario | Expected |
|---|---|---|
| F-01 | Invalid Groq key | Report arrives: "AI unavailable", PARTIAL |
| F-02 | RDAP/DNS unreachable (block outbound DNS or use an unknown TLD) | C5/C6 `UNAVAILABLE` and named; PARTIAL |
| F-03 | Kill the app right after a report is saved, then restart | Sweeper re-sends → analysis re-sends completion → READY |
| F-04 | Stop Kafka, upload, start Kafka | 202, then QUEUED → READY |
| F-05 | Produce malformed JSON to `submission.created` | Lands in `submission.created.DLT` without retries |
| F-06 | Two TPO tabs decide the same case | One 200, one 409; the UI refreshes |

## 4. Test data

- **Synthetic letters** in `src/test/resources/letters/`:
  - genuine-style offer (fictional company)
  - deposit scam
  - Hinglish fee scam
  - "no registration fee is charged" genuine
  - "no interview, pay fee" scam
  - injection text
  - copied genuine letter with altered payment details
  - WhatsApp-only contact
  - pressure language
- **Synthetic PDFs:** 1-page valid, 11-page, encrypted, image-only, `.exe` renamed to `.pdf`, and a slow/bomb-style file.
- **Fake identifiers:** phones `+91 99999 0xxxx`; UPI IDs `test.*@upi`; domains `.example`/`.test` where no real lookup is needed. Real brand names appear only as domain strings (e.g. the PRD's Infosys example).

## 5. Manual journeys (via `http://localhost`)

| ID | Role | Journey |
|---|---|---|
| J1 | New user | Google login → Continue on my own → upload text → report → My submissions |
| J2 | Student + TPO | Request to join a college → TPO approves (If-Match) → email arrives → the student sees APPROVED |
| J3 | Off-campus victim | Upload the deposit scam → High → Report scam → a second user uploads the same letter → "Reported as a scam by 1 user (not verified)" |
| J4 | Admin | Unverified queue → open the PDF link → Confirm → reporter emailed → a third user's identical letter → **Confirmed scam**, High → Revert → no longer confirmed |
| J5 | TPO | Student clicks "Ask my TPO" → review queue → open evidence + PDF link → CONFIRMED_SCAM → a second TPO tab gets 409 → student emailed |
| J6 | Admin | Known companies: add a company → a lookalike letter is flagged (C3 + C4) |

## 6. Security proofs (run at M2 and M7; paste evidence into §7)

| ID | Control | How | Expected |
|---|---|---|---|
| S-01 | Session cookie (§14.2) | Browser devtools after login | `JSESSIONID`: HttpOnly; Secure; SameSite=Lax |
| S-02 | Only Caddy is public (§14.7) | `docker compose ps --format "{{.Name}} {{.Ports}}"`; `curl -m 3 http://localhost:8080` | Only Caddy has ports; the direct call fails |
| S-03 | IDOR (§14.3) | Two Google accounts: A requests B's submission, report and PDF link IDs | 404 each |
| S-04 | CSRF (§14.2) | `curl -X POST` an API with the session cookie but no `X-XSRF-TOKEN` | 403 |
| S-05 | SSRF (§14.5) | Submit with sender `x@127.0.0.1` and `x@[::1]` | No outbound call; C5/C6 not run |
| S-06 | File validation (§14.4) | Upload an `.exe` renamed to `.pdf`, a 6 MB file, an 11-page PDF | 415 / 413 / 422 |
| S-07 | Rate limits (D-12) | 6 uploads in 10 min; 61 requests in a minute; 6 scam reports in a day | 429 each time |
| S-08 | Security headers (§14.7, D-10) | `curl -I http://localhost/` and an API path | CSP, nosniff, frame-ancestors, Referrer-Policy (+ HSTS in prod) |
| S-09 | Actuator (§14.7) | `curl http://localhost/actuator/env` | Not routed; inside the container only `health` is exposed |
| S-10 | Secrets (§14.7) | `git check-ignore .env`; gitleaks in CI | Ignored; 0 leaks |
| S-11 | No PII in logs (§14.6, D-27) | Plant markers (`+91 99999 01234`, `marker.s11@upi`, `ZEBRA-MARKER-S11`); run J1–J5; grep `logs/` and the DLTs | Zero hits |
| S-12 | Encryption at rest (§14.6) | `SELECT HEX(LEFT(text_cipher,16)) FROM submission LIMIT 1` | Opaque bytes |

**OWASP 2021 mapping:** A01 → S-03 · A02 → S-01, S-12, TLS · A03 → `PromptInjectionTest`, JPA parameters, no `[innerHTML]` · A04 → scoring/guard tests · A05 → S-02, S-08, S-09 · A06 → Dependabot · A07 → S-01, P2 tests · A08 → CI + pinned images · A09 → audit log + request ID logs · A10 → S-05.

## 7. Results log (fill in during execution; never pre-fill)

| Date | Phase/Task | Command or proof ID | Executed? | Passed? | Evidence |
|---|---|---|---|---|---|
| 2026-10-19 | P1-01 | `backend/mvnw -B -f backend/pom.xml verify` | Yes | Yes | `BUILD SUCCESS`: 14 unit tests + 2 integration tests, 0 failures, 0 errors |
| 2026-10-19 | P1-03 | `LoggingAspectTest` (`logsMethodAndDuration`, `neverLogsArguments`, `neverLogsReturnValue`, `exception_loggedAsClassOnly_rethrown`) | Yes | Yes | 4/4; marker strings absent from the captured log |
| 2026-10-19 | P1-03 | `RequestIdFilterTest` (MDC + header, incoming header ignored, MDC cleared) | Yes | Yes | 3/3 |
| 2026-10-19 | P1-03 | `ProblemDetailExceptionHandlerTest` (404, 409, 422, 500 without internals, 500 logs class only, unknown path) | Yes | Yes | 6/6; `application/problem+json` |
| 2026-10-19 | P1-03 | `PingControllerTest` | Yes | Yes | 1/1; `X-Request-Id` header present |
| 2026-10-19 | P1-03 · V-25 | `FlywayMigrationIT` on `mysql:8.4` (Testcontainers) | Yes | Yes | 2/2; `VERSION()` 8.4; "Successfully applied 2 migrations to schema `test`, now at version v2"; duplicate `processed_event` key rejected |
| 2026-10-19 | P1-02 | `docker compose up -d --build` + `docker compose ps` | Yes | Yes | app, caddy, kafka, mysql all `(healthy)` |
| 2026-10-19 | P1-02 · S-02 | `docker compose port app 8080` | Yes | Yes (by intent) | Compose v5.1.4 printed `invalid IP:0` (exit 0) instead of nothing. The port is not published, shown three ways: `docker port offershield-app-1` prints nothing; `docker compose ps` shows app `8080/tcp` with no host binding; `curl -m 3 http://localhost:8080` → exit 7. Only Caddy has `127.0.0.1:80->80/tcp` |
| 2026-10-19 | P1-02 | `curl http://localhost/api/ping` / `curl http://localhost/actuator/health` (through Caddy) | Yes | Yes | `200 {"status":"up"}` / `404` (actuator not routed) |
| 2026-10-19 | P1-02 · S-09 (part) | Inside the app container: `/actuator/health`, `/actuator/env` | Yes | Yes | `{"status":"UP"}` / `404` |
| 2026-10-19 | P1-02 | Kafka broker config (`server.properties`) | Yes | Yes | `auto.create.topics.enable=false`, `log.retention.hours=72`, `log.dirs=/var/lib/kafka/data` |
| 2026-10-19 | P1-02 | `SHOW GRANTS` as `offershield_app` | Yes | Yes | `SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, REFERENCES, INDEX, ALTER ON offershield_db.*` only; app container runs as uid 10001 |
| 2026-10-19 | P1-03 · V-25 | Flyway in the compose stack (`docker compose logs app`) | Yes | Yes | "Database: jdbc:mysql://mysql:3306/offershield_db (MySQL 8.4)" · "Successfully applied 2 migrations to schema `offershield_db`, now at version v2" |
| 2026-10-19 | P1-03 | `grep requestId logs/offershield.log` | Yes | Yes | `requestId=7b193f01-9b57-42ca-98ff-8e1168001589 userId=- submissionId=- … call=PingController.ping durationMs=0 outcome=ok` |
| 2026-10-19 | P1-01 · S-10 (part) | `git check-ignore .env` | Yes | Yes | prints `.env` |
| 2026-10-19 | P1-01 | CI workflow (build + gitleaks) and Dependabot files exist | Yes | Yes | `.github/workflows/ci.yml`, `.github/dependabot.yml` |
| 2026-10-19 | P1-01 · S-10 (part) | CI run + gitleaks on GitHub | No | — | No GitHub remote yet (M1 item 6: CI runs once a remote exists) |
| 2026-10-20 | P1 review fix · S-11 (part) | `SqlErrorLoggingIT.duplicateKeyViaHibernate_valueNeverLogged` (root-logger appender, duplicate key via Hibernate) | Yes | Yes | RED first: `org.hibernate.orm.jdbc.error - Duplicate entry '…zebra.marker.sql@example.com'…` logged; GREEN after silencing `org.hibernate.orm.jdbc.error/warn`; full verify 14 unit + 3 IT green |
| 2026-10-20 | P1 review fix | `APP_DOMAIN=demo.duckdns.org docker compose config` | Yes | Yes | `SITE_ADDRESS: demo.duckdns.org`; without APP_DOMAIN → `http://localhost` |
| 2026-10-20 | P1-02 (re-capture) | `kafka-configs.sh --describe --all` (broker 1) after rebuild | Yes | Yes | `auto.create.topics.enable=false`, `log.dirs=/var/lib/kafka/data`, `log.retention.hours=72`; all 4 services healthy |
| 2026-10-20 | P2 | `backend/mvnw -B -f backend/pom.xml verify` | Yes | Yes | `BUILD SUCCESS`: 73 surefire tests + 16 failsafe ITs (MySQL 8.4 Testcontainers), 0 failures |
| 2026-10-20 | P2-02 | `OidcUserProvisioningServiceTest` (12, incl. `newSub_createsUser`, `existingSub_emailChange_sameRow`, `verifiedAdminEmail_admin`, `verifiedAdminEmail_caseInsensitive_admin`, `verifiedTpoAssignment_becomesTpoAndAuditWritten`, `unverifiedEmail_userOnly`) + `UserProvisioningIT` (6) | Yes | Yes | R-C guard proven: removing the `emailVerified` checks made `unverifiedEmail_userOnly` fail |
| 2026-10-20 | P2-02 | `LoginRedirectTest.containsStateNonceAndPkce`, `SessionCookieTest` (2), `ApiUnauthenticatedTest` (3), `MeControllerTest` (2), `OidcLoginUserServiceTest` (2), `MethodSecurityTest` (2) | Yes | Yes | all pass |
| 2026-10-20 | P2-03 | `CsrfTest` (4), `SecurityHeadersTest` (3), `UserRateLimitFilterTest` (5, incl. 61st → 429, separate buckets), `UserMdcFilterTest` (4) | Yes | Yes | all pass |
| 2026-10-20 | P2-01 | Flyway V3–V5 in the compose stack | Yes | Yes | "Successfully applied 3 migrations to schema `offershield_db`, now at version v5"; `EntityMappingIT` validates entities (`ddl-auto=validate`) |
| 2026-10-20 | P2 · S-01 (part) | `curl -D - http://localhost/oauth2/authorization/google` (through Caddy) | Yes | Yes | 302 to Google with `state`, `nonce`, `code_challenge`, `code_challenge_method=S256`, `redirect_uri=http://localhost/login/oauth2/code/google` (forward headers work); `Set-Cookie: JSESSIONID=…; Path=/; Secure; HttpOnly; SameSite=Lax` |
| 2026-10-20 | P2 · S-01 (browser) | Devtools cookie check after a real Google sign-in | No | — | Google client ID/secret deferred by the user until after all phases |
| 2026-10-20 | P2 | Sign in at `http://localhost/oauth2/authorization/google`; `/api/auth/me` shows the role | No | — | Same: needs `GOOGLE_CLIENT_ID/SECRET` and `ADMIN_EMAILS` |
| 2026-10-20 | P2 · S-02 | `docker compose ps`; `curl -m 3 http://localhost:8080/api/ping` | Yes | Yes | only `offershield-caddy-1 127.0.0.1:80->80/tcp`; direct call → exit 7 |
| 2026-10-20 | P2 · S-04 | `curl -X POST http://localhost/api/auth/logout` without `X-XSRF-TOKEN` | Yes | Yes | `403` problem+json, `errorCode` `CSRF_INVALID` (with a session as well: `CsrfTest.postWithoutToken_403`) |
| 2026-10-20 | P2 | `curl http://localhost/api/auth/me` without a session | Yes | Yes | `401` problem+json `UNAUTHORIZED`, no redirect |
| 2026-10-20 | P2 · S-08 (API path) | `curl -I http://localhost/api/ping` | Yes | Yes | CSP (D-10 string), `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `X-Frame-Options: DENY`; HSTS only on secure requests (`SecurityHeadersTest.secureRequest_hasHsts`). `/` (frontend) headers: P4 |
| 2026-10-20 | P2 · S-09 | `curl http://localhost/actuator/env`; inside the container `/actuator/env`, `/actuator/health` | Yes | Yes | via Caddy `404` (not routed); in-container `401` (secured, and only `health` is exposed); health `UP` |
