# 04 — Work Breakdown (task-level plan)

> **For agentic workers:** Run one phase at a time with its prompt in [07](07-phase-execution-prompts.md). At the start of each phase:
> 1. Ask the user that phase's **M-items** from [01 §3](01-prd-review-and-open-decisions.md).
> 2. Use `superpowers:writing-plans` to expand the phase's tasks into TDD steps.
> 3. Execute them with `superpowers:executing-plans` or `superpowers:subagent-driven-development`.
>
> Steps use checkbox (`- [ ]`) syntax.

**Goal:** build OfferShield v1 as decided in [01 §1](01-prd-review-and-open-decisions.md), in the PRD §17 order, as small verifiable tasks.

**Architecture:** one Spring Boot 4.1 app (feature packages) + Kafka (2 topics) + MySQL 8.4 + Angular 22, behind Caddy. See [02](02-system-architecture-and-adrs.md).

**Spec:** [`OfferShield_PRD.md`](../../OfferShield_PRD.md) as amended by the decision log (D-01…D-33).

## Global constraints (apply to every task)

- **Decisions win over PRD text** where they differ (D-xx). For anything not covered, stop and ask (user rule: no silent decisions).
- **Identity** comes from the Spring Security session only, never from request headers.
- **Scoped queries:** another user's data → **404**; a wrong role → 403.
- **Errors:** RFC 9457 `ProblemDetail`.
- **Logging (D-27):** the AOP aspect logs class.method, user ID, submission ID, duration and errors, plus a request ID. **Never arguments, return values, letter text, emails, phones, UPI IDs or secrets.**
- **Timeouts:** Groq 10 s, RDAP 5 s, DNS 3 s, Cloudinary 15 s, SMTP 10 s. A failed lookup → `UNAVAILABLE` → report `PARTIAL`.
- **Kafka (D-28):**
  - Publish after commit; deterministic event IDs.
  - `processed_event` (`consumer`, `event_id`) written in the same transaction as the work.
  - 3 retries, then `<topic>.DLT`, with the resolver set explicitly.
  - Trusted package `com.offershield.common.events`.
- **Flyway:** `V{n}__desc.sql`, one DDL change per file, never edit an applied migration.
- **Tests:** JUnit 5 + AssertJ + Mockito; MySQL Testcontainers for DB tests (no H2); **fake letters only**.
- **Never cut:**
  - SSRF guard, IDOR scoping, file validation, AI fallback-to-none (PRD §17).
  - Every §14 control (D-25).
  - The PRD §17 item "header stripping" is satisfied by design: no header-based identity exists.

## Review focus (failure modes most likely to hurt real users; each has a pinned test)

1. **A genuine "no fee is charged" letter flagged High** → `MoneyRequestCheckTest.negatedFeeMention_notTriggered` (P6-02, D-09)
2. **A Gmail-sent scam marks `gmail.com` itself as a scam domain** → `IndicatorFactoryTest.freeMailDomain_neverBecomesIndicator` (P9-03, D-08)
3. **AI lowers a score through the copied-letter guard** → `CopiedLetterGuardTest.aiOnlyCompany_noDowngrade` (P9-04, R-A)
4. **A crash after the report is saved leaves the submission stuck in "Processing"** → `SubmissionCreatedListenerTest.duplicateWithExistingReport_resendsCompletion` (P6-07, D-28)
5. **A double-clicked upload creates two submissions** → `IdempotencyKeyTest.concurrentDoubleClick_oneRowSameId` (P5-01, D-33.2)

---

## P0 — Before coding (user tasks)

### P0-01 · Accounts and secrets (free only)
- **Est:** 1.5 h (user)
- **Acceptance:**
  - A git-ignored `.env` holds every variable in [06 §2](06-deployment-and-verification.md).
  - Google OAuth client with redirect `http://localhost/login/oauth2/code/google`.
  - Groq account created (D-30; it works from India, or the result is recorded), with Zero Data Retention on.
  - Cloudinary free account.
  - Dedicated Gmail with 2-Step Verification and an app password.
  - AES key, HMAC pepper and DB password generated locally.
  - The OCI account can wait until P14.
- **Verify:** `git check-ignore .env` prints `.env`.

### P0-02 · Verify the seed company list (V-21, D-24)
- **Est:** 0.5 h (user)
- **Acceptance:** ~10 companies, each with an official email domain checked on the company's own website, are written into 01 §6 V-21.

---

## P1 — Foundation (PRD H0–2)

### P1-01 · Repository skeleton, CI, Dependabot
- **Decisions:** D-01, D-27, M-01, M-02 · **Est:** 1.0 h
- **Files:** `.gitignore` (`.env`, `logs/`, `target/`, `node_modules/`, `dist/`, `.angular/`); `.env.example` (names only); `backend/pom.xml` (Boot 4.1 parent; Java 21); `backend/src/main/java/com/offershield/OfferShieldApplication.java`; `.github/workflows/ci.yml` (JDK 21 `mvn -B -f backend/pom.xml verify`, gitleaks on full history; frontend job added in P4); `.github/dependabot.yml` (maven, npm, docker, github-actions).
- **Acceptance:** `mvn -B -f backend/pom.xml verify` is green.
- **Verify:** `mvn -B -f backend/pom.xml verify` · `git check-ignore .env`

### P1-02 · Docker Compose (MySQL 8.4, Kafka, Caddy, app)
- **Decisions:** D-16, D-23, D-32, M-03 · **Est:** 1.25 h
- **Files:** `docker-compose.yml`; `infra/mysql/init/01-app-user.sh` (creates `offershield_db` and user `offershield_app` with rights on that schema only); `Caddyfile` (local site `http://localhost`: `/api/*`, `/oauth2/*`, `/login/*` → `app:8080`; everything else → frontend, added in P4); `backend/Dockerfile` (multi-stage; `eclipse-temurin:21-jre`; non-root).
- **Acceptance:**
  - Images: `mysql:8.4` and `apache/kafka:4.3.1` (KRaft, single-broker settings, `auto.create.topics.enable=false`, default retention 3 days).
  - Healthchecks on every service; `depends_on: service_healthy`.
  - **Only Caddy publishes a port** (`127.0.0.1:80:80`). MySQL, Kafka and the app are internal.
- **Verify:** `docker compose up -d --build && docker compose ps` (all healthy; the app's healthcheck calls `/actuator/health` inside its container) · `docker compose port app 8080` prints nothing (not published) · `curl -s -o /dev/null -w "%{http_code}" http://localhost/actuator/health` does **not** reach the app (Caddy only routes `/api`, `/oauth2` and `/login`).

### P1-03 · Common basics: ProblemDetail, AOP logging, Flyway on 8.4
- **Decisions:** D-27, D-32 · **Est:** 1.25 h
- **Files:** `common/web/ProblemDetailExceptionHandler.java` (+ `NotFoundException`, `ConflictException`, `UnprocessableException`); `common/logging/LoggingAspect.java` (`@Around` on `@RestController`, `@Service` and `@KafkaListener` beans); `common/logging/RequestIdFilter.java` (MDC `requestId`); `logback-spring.xml` (console + `logs/offershield.log`, daily rolling, `maxHistory` 14); `db/migration/V1__baseline.sql` (`audit_log`, `processed_event`); `application.yml` (actuator `health` only, ProblemDetails enabled).
- **Acceptance:**
  - Flyway applies V1 on `mysql:8.4` (**proves V-25**; on failure, stop and ask, per D-32).
  - One request writes log lines with `requestId`, `class.method` and `durationMs` to `logs/offershield.log`.
  - Method arguments never appear.
- **Tests:** `LoggingAspectTest`: `logsMethodAndDuration`, `neverLogsArguments` (calls with a marker string; asserts the marker is absent from the captured log); `ProblemDetailExceptionHandlerTest` (404, 409, 422, 500 without internals).
- **Verify:** `mvn -B -f backend/pom.xml verify`; `docker compose up -d --build`; `grep requestId logs/offershield.log`.

**Checkpoint P1:** What does each container do? Why is only Caddy published? What does the AOP aspect log, and what does it refuse to log?

---

## P2 — Login and security (PRD H2–8: replaces identity + gateway)

### P2-01 · Users and TPO assignment schema
- **Decisions:** D-02 · **Est:** 0.5 h
- **Files:** `V2__app_user.sql`, `V3__college.sql`, `V4__tpo_assignment.sql`
- **Acceptance:**
  - `app_user.google_sub` UNIQUE NOT NULL; `role` is one of USER, TPO, ADMIN; `tpo_college_id` nullable.
  - `tpo_assignment.email` UNIQUE.

### P2-02 · Google sign-in, provisioning, session
- **PRD:** §3, §6.1, §14.1, §14.2 · **Decisions:** D-02, D-21, R-C, M-04 · **Est:** 2.0 h (1.5–3)
- **Files:** `auth/SecurityConfig.java`, `auth/OidcUserProvisioningService.java`, `auth/AdminProperties.java`, `auth/MeController.java`, `auth/CurrentUser.java`
- **Acceptance:**
  - Login: `oauth2Login` with Google, scopes `openid email profile`. The redirect has `state`, `nonce` and PKCE.
  - Provisioning:
    - Users are looked up and created by `sub`; email and name update on each login.
    - A verified email in `ADMIN_EMAILS` → ADMIN.
    - A verified email matching `tpo_assignment` → TPO for that college; the assignment is consumed and an `audit_log` row written.
    - An unverified email → USER only (R-C).
  - Session cookie: HttpOnly, Secure, SameSite=Lax, lifetime per M-04.
  - `server.forward-headers-strategy=framework`, so the OAuth redirect URI uses Caddy's public scheme and host (PRD §14.2 "real gotcha").
  - `GET /api/auth/me` returns userId, name, email, role, college and membership status.
  - `POST /api/auth/logout` invalidates the session.
  - Unauthenticated `/api/**` → 401 ProblemDetail, not a redirect.
- **Tests:**
  - `OidcUserProvisioningServiceTest`: `newSub_createsUser`, `existingSub_emailChange_sameRow`, `verifiedAdminEmail_admin`, `verifiedTpoAssignment_becomesTpoAndAuditWritten`, `unverifiedEmail_userOnly`
  - `LoginRedirectTest.containsStateNonceAndPkce`
  - `SessionCookieTest.attributesHttpOnlySecureLax`
  - `ApiUnauthenticatedTest.returns401NotRedirect`

### P2-03 · CSRF, security headers, per-user rate limit
- **PRD:** §12 (XSRF), §13.6, §14.2, §14.7 · **Decisions:** D-10, D-12, V-03 · **Est:** 1.5 h (1–2.5)
- **Files:** `SecurityConfig` additions; `auth/UserRateLimitFilter.java` (Bucket4j, in-memory, keyed by user ID)
- **Acceptance:**
  - `csrf(c -> c.spa())`, so the `XSRF-TOKEN` cookie is readable by JS.
  - Headers: CSP `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'`; HSTS (prod profile); nosniff; Referrer-Policy.
  - The 61st request within a minute from one user → 429.
- **Tests:** `CsrfTest` (`postWithoutToken_403`, `postWithToken_ok`, `getIssuesXsrfCookie`), `SecurityHeadersTest`, `UserRateLimitFilterTest` (`61stRequest_429`, `twoUsers_separateBuckets`)
- **Verify:** `mvn -B -f backend/pom.xml verify`. Then sign in at `http://localhost/oauth2/authorization/google` and check that `/api/auth/me` shows your role.

**Checkpoint P2:** the OAuth Authorization Code flow; session cookie vs CSRF token; why there are no identity headers; 404 vs 403.

---

## P3 — Colleges and membership (PRD H8–10; path C only, D-17)

### P3-01 · Admin: colleges and TPO assignment
- **PRD:** §3, §11 · **Decisions:** D-02, R-E · **Est:** 1.0 h
- **Files:** `college/CollegeAdminController.java`, `college/CollegeService.java`, `college/CollegeSearchController.java`
- **Acceptance:**
  - `POST /api/admin/colleges {name, city}` (ADMIN; others 403).
  - `POST /api/admin/colleges/{id}/tpos {email}`: if the user already exists, they're promoted now; otherwise a `tpo_assignment` is created. Either way an audit row is written.
  - `GET /api/colleges?q=` returns at most 20 results.
  - `GET /api/admin/colleges` lists colleges with their TPO emails.
- **Tests:** `CollegeAdminControllerTest` (`nonAdmin_403`, `assignExistingUser_promoted`, `assignNewEmail_pending`, `audited`)

### P3-02 · Join request, TPO decision, email
- **PRD:** §3.1 C, §4 F2, F9, §13.8 · **Decisions:** D-22, D-33.3, D-33.7, R-E · **Est:** 1.5 h
- **Files:** `membership/Membership.java` (`V5__membership.sql`), `MembershipController.java`, `MembershipService.java`, `notification/EmailService.java` (Spring Mail, sent after commit; failures logged)
- **Acceptance:**
  - `POST /api/memberships {collegeId, rollNo, department}` → PENDING. An existing PENDING or APPROVED request → 409. A rejected student may re-apply.
  - `GET /api/memberships/pending` (TPO): own college only.
  - `POST /api/memberships/{id}/decision {decision, reason}` with `If-Match`:
    - a stale version → 409
    - another college → 404
    - a reject without a reason → 400
  - Each decision writes an audit row, and the email goes to the student.
- **Tests:**
  - `MembershipDecisionTest`: `approve_ok_emailSent_audited`, `staleVersion_409`, `otherCollege_404`, `rejectWithoutReason_400`, `rejectedCanReapply`
  - `EmailServiceTest.smtpFailure_loggedDecisionKept`
- **Verify:** `mvn -B -f backend/pom.xml verify`; run the flow manually with curl and a test Gmail inbox.

**Checkpoint P3:** the membership state machine; what `If-Match` prevents; why the email is sent after commit.

---

## P4 — Angular shell (PRD H10–11)

### P4-01 · App foundation
- **PRD:** §12 · **Decisions:** D-01, D-10, D-14, D-26, M-05 · **Est:** 2.0 h (1.5–3)
- **Files:**
  - `frontend/`, created with the `angular-new-app` skill (Angular 22).
  - `core/auth/session.ts`, `auth.guard.ts`, `role.guard.ts`; `core/http/unauthorized.interceptor.ts`, `problem.ts`.
  - `app.routes.ts`: `/login`, `/start`, `/join`, plus lazy `/app`, `/tpo`, `/admin` using `canMatch`.
  - `styles/tokens.css`.
  - Serving per M-05 (nginx `Dockerfile`, or Caddy `file_server`); a Caddy route for the frontend; the CI frontend job.
- **Acceptance:**
  - Standalone components, signals, `@if`/`@for`.
  - One interceptor; relative `/api` URLs.
  - No `[innerHTML]`.
  - `inlineCritical: false`; self-hosted fonts.
- **Tests:** `auth.guard.spec`, `role.guard.spec`, `unauthorized.interceptor.spec`
- **Verify:** `cd frontend && npm ci && npx ng test --watch=false && npx ng build` · `grep -r "innerHTML" frontend/src` (no matches)

### P4-02 · Login, Choose path, Join college
- **Est:** 1.0 h
- **Acceptance:**
  - Login is a full-page navigation to `/oauth2/authorization/google`.
  - Choose path: "Join my college" or "Continue on my own".
  - Join college shows PENDING and handles 409.
- **Tests:** `join-college.spec` (`submit_showsPending`, `409_showsExisting`)
- **Verify:** click through at `http://localhost`.

**Checkpoint (end of Day-1 scope):** the OAuth flow; how CSRF works with Angular; guards vs server checks.

---

## P5 — Submission (PRD H24–27)

### P5-01 · Schema, upload endpoint, Idempotency-Key, DB-count limit, owner reads
- **PRD:** §4 F3, §6.2, §10, §13.4, §14.3 · **Decisions:** D-12, D-33.1, D-33.2, D-33.6 · **Est:** 1.5 h
- **Files:** `V6__submission.sql`, `submission/SubmissionController.java`, `SubmissionService.java`, `SubmissionRepository.java`
- **Acceptance:**
  - The PRD columns + `publish_attempts`, `last_published_at`; UNIQUE(`owner_user_id`, `idempotency_key`).
  - `POST /api/submissions` takes multipart `file` XOR `text`, plus optional `senderEmail`, `recruiterPhone` and `upiId`, with a required `Idempotency-Key`:
    - → 202 `{submissionId}`
    - a replay with the same content → the same ID
    - different content → 422
    - a 6th upload within 10 min → 429 (DB count)
  - `GET /api/submissions` and `GET /api/submissions/{id}` are owner-scoped (404 otherwise).
- **Tests:**
  - `IdempotencyKeyTest`: `replay_sameId`, `differentContent_422`, `missingKey_400`, `concurrentDoubleClick_oneRowSameId` (Testcontainers)
  - `UploadLimitTest.sixthIn10Min_429`
  - `SubmissionAccessTest`: `otherUsersSubmission_404`, `own_200`, `list_onlyOwn` (Testcontainers, seeded rows)

### P5-02 · File validation and text extraction
- **PRD:** §14.4 · **Decisions:** D-33.1 · **Est:** 1.0 h
- **Files:** `submission/FileValidator.java`, `TextExtractor.java`; synthetic PDF fixtures
- **Acceptance:**
  - ≤ 5 MB, otherwise 413.
  - Tika detects `application/pdf`, otherwise 415.
  - ≤ 10 pages, otherwise 422.
  - Encrypted → 422.
  - PDFBox runs with a memory limit and a 10 s timeout (→ 422).
  - Fewer than 50 extracted characters → 422 "paste the text instead".
  - Pasted text ≤ 20,000 chars.
- **Tests:** `FileValidatorTest`: `exeRenamed_415`, `oversize_413`, `elevenPages_422`, `encrypted_422`, `timeout_422`, `imageOnly_422`, `validOnePage_ok`

### P5-03 · AES-256-GCM text encryption
- **PRD:** §10.1, §14.6 · **Est:** 0.5 h
- **Files:** `common/crypto/TextCipher.java`
- **Acceptance:** a 32-byte key from `TEXT_AES_KEY_BASE64`, checked at startup; a random 12-byte IV; AAD = submissionId; a `key_version` column.
- **Tests:** `TextCipherTest`: `roundTrip`, `tampered_fails`, `wrongAad_fails`, `ivUnique`, `badKey_failsStartup`

### P5-04 · Cloudinary private storage
- **PRD:** §9.1 · **Decisions:** D-05 · **Est:** 1.0 h
- **Files:** `submission/CloudinaryStore.java` (`cloudinary-http5`)
- **Acceptance:**
  - Upload with `type=private` **after** validation. `public_id` = `submissions/` + sha256(owner:idempotencyKey).
  - `delete(publicId)` treats not-found as OK.
  - `downloadLink(publicId, ttl)` uses `privateDownload(..., expires_at)` and is used in P10.
  - **Day-1 check (V-10):** generate a link for a test PDF with the "Allow delivery of PDF" setting off and then on, and record the result in 01 §6.
- **Tests:** `CloudinaryStoreTest` (mocked SDK: `upload_privateType`, `delete_notFoundOk`, `link_hasExpiry`); `SubmissionServiceTest.invalidPdf_cloudinaryNeverCalled`

### P5-05 · Publish after commit + sweeper
- **PRD:** §13.3 · **Decisions:** D-20, D-28 · **Est:** 1.0 h
- **Files:** `submission/SubmissionEventPublisher.java` (`@TransactionalEventListener(AFTER_COMMIT)`), `QueuedSubmissionSweeper.java`, `common/kafka/KafkaTopicsConfig.java` (both topics + DLTs, 3 partitions, retention per D-06)
- **Acceptance:**
  - The event is published only after commit; eventId = name-based UUID; the payload follows 02 §5 (no letter text).
  - Sweeper thresholds: 2, 4, 8, 16 min; after 4 re-publishes → FAILED.
  - PRD done-when: an upload returns 202 and the event is visible in Kafka.
- **Tests:**
  - `SubmissionEventPublisherTest`: `rolledBack_noSend`, `committed_sendsDeterministicId`
  - `QueuedSubmissionSweeperTest`: `stale_republished`, `fresh_skipped`, `backoffDoubles`, `cap_marksFailed`
- **Verify:** `mvn -B -f backend/pom.xml verify`; upload via `http://localhost`; read one message from `submission.created` with `kafka-console-consumer.sh`.

**Checkpoint P5:** the idempotency key; publish after commit and what the sweeper fixes; GCM IV and AAD.

---

## P6 — Analysis core (PRD H27–31, H34–35 status update)

### P6-01 · Schema and `submission.created` consumer
- **PRD:** §6.2, §10, §13.4, §13.5 · **Decisions:** D-28 · **Est:** 1.0 h
- **Files:** `V7__report_signal.sql`, `analysis/SubmissionCreatedListener.java` (group `analysis`), `analysis/AnalysisService.java`, `common/kafka/KafkaConsumerConfig.java`
- **Acceptance:**
  - `ErrorHandlingDeserializer`; 3 retries → `submission.created.DLT` (explicit name); bad payloads aren't retried.
  - `max.poll.records=1`.
  - External calls run outside the DB transaction; one short transaction saves `processed_event` + report + signals.
- **Tests:** `SubmissionCreatedListenerTest`: `firstDelivery_analyses`, `duplicate_skipped`, `poison_toDltNoRetry`

### P6-02 · RiskCheck strategy: C1, C2, C8, C9
- **PRD:** §7.1, §7.2 · **Decisions:** D-09, D-33.6 · **Est:** 1.25 h
- **Files:** `analysis/checks/RiskCheck.java`, `CheckResult.java`, `MoneyRequestCheck.java`, `FreeMailSenderCheck.java`, `OffPlatformContactCheck.java`, `PressureLanguageCheck.java`; `free-mail-domains.txt`; fake letter fixtures
- **Acceptance:**
  - C1: a hard override, including Hinglish variants, with the D-09 "no fee" rule.
  - C2 +25 · C8 +15 · C9 +10 each, max 2.
  - Each result carries its evidence snippet and source kind.
- **Tests:**
  - `MoneyRequestCheckTest`: `deposit_triggered`, `hinglish_triggered`, `negatedFeeMention_notTriggered`, `negatorOnOtherNoun_triggered` ("No interview, pay registration fee today")
  - `FreeMailSenderCheckTest`, `OffPlatformContactCheckTest`, `PressureLanguageCheckTest.cappedAtTwo`

### P6-03 · DomainValidator (SSRF guard) — never cut
- **PRD:** §14.5, §18 · **Decisions:** M-06 · **Est:** 0.75 h
- **Files:** `analysis/lookup/DomainValidator.java`, `ValidatedDomain.java`
- **Acceptance:**
  - Rejects IP literals (dotted, decimal, hex, IPv6), ports, paths, schemes, userinfo, `localhost`, `.local`/`.internal`, single-label names and over-long names. Runs `IDN.toASCII`.
  - Derives the registrable domain per M-06.
- **Tests:** `DomainValidatorTest` (PRD inputs `127.0.0.1`, `[::1]`, `169.254.169.254`, `evil.com:8080/x`, `http://x`, plus `2130706433`, `0x7f.0.0.1`, `localhost` → rejected; `hr.inf0sys-careers.co.in` → `inf0sys-careers.co.in`)

### P6-04 · RDAP client and C5
- **PRD:** §7.1 C5, §13.7 · **Decisions:** D-19, D-31 · **Est:** 1.0 h
- **Files:** `analysis/lookup/RdapBootstrap.java`, `RdapClient.java`, `analysis/checks/YoungDomainCheck.java`
- **Acceptance:**
  - Hosts come only from the IANA bootstrap (cached 24 h). HTTPS only; no redirects off the list.
  - Timeout 5 s; body ≤ 256 KB; results cached 24 h.
  - Under 90 days → +20; under 1 year → +10; no data or an error → `UNAVAILABLE`.
- **Tests:** `RdapClientTest`: `tldNotInBootstrap_unavailable`, `redirectOffList_refused`, `timeout_unavailable`, `cached_noSecondCall`, `missingRegistrationEvent_unavailable`; `YoungDomainCheckTest` at 89/90/364/365 days

### P6-05 · DNS client and C6
- **PRD:** §7.1 C6 · **Decisions:** D-19, D-31 · **Est:** 0.75 h
- **Files:** `analysis/lookup/DnsClient.java` (dnsjava), `analysis/checks/MailSetupCheck.java`
- **Acceptance:** MX lookup; DMARC = a TXT record at `_dmarc.<domain>` starting `v=DMARC1`. Timeout 3 s; cached 1 h. No MX +15; no DMARC +5; a failure → `UNAVAILABLE`.
- **Tests:** `MailSetupCheckTest`: `noMx_15`, `noDmarc_5`, `timeout_unavailable`

### P6-06 · ScoringEngine and bands
- **PRD:** §2.1, §7.2, §7.3, §18 · **Est:** 1.0 h
- **Files:** `analysis/scoring/ScoringEngine.java`, `Band.java`, `Score.java`
- **Acceptance:**
  - Points are summed and capped at 100.
  - **Only C1, C10 and C11** can override to High; C12 never can.
  - Bands 0–24 / 25–49 / ≥ 50, with the PRD §7.2 messages verbatim.
- **Tests:** `ScoringEngineTest`: `moneyRequest_high`, `onlyC1C10C11_canOverride`, `unverifiedOnly_neverOverrides`, `bandBoundaries`, `cappedAt100`

### P6-07 · Save report, publish `analysis.completed`, status consumer, owner read
- **PRD:** §5.3 fan-out, §6.2, §6.3, §11 · **Decisions:** D-04, D-14, D-20, D-28 · **Est:** 1.25 h
- **Files:** `analysis/ReportService.java`, `AnalysisCompletedPublisher.java`, `ReportController.java`; `submission/SubmissionStatusListener.java` (group `submission-status`)
- **Acceptance:**
  - `partial = true` when any applicable check or AI was unavailable.
  - `analysis.completed` is published after commit (02 §5 payload).
  - **Re-send fix:** if a duplicate `submission.created` arrives and the report already exists, re-publish.
  - The status consumer moves `QUEUED → READY/PARTIAL`, with dedupe and a DLT.
  - `GET /api/reports/{id}` is owner-only.
  - PRD done-when: a "pay ₹5,000 deposit" letter → High with C1 evidence.
- **Tests:**
  - `SubmissionCreatedListenerTest.duplicateWithExistingReport_resendsCompletion`
  - `SubmissionStatusListenerTest`: `queued_toReady`, `partial_toPartial`, `duplicate_ignored`, `poison_toDlt`
  - `ReportAccessTest` (Testcontainers): `otherUsersReport_404`, `own_200`, `foreignAndRandomId_sameBody`
  - `AnalysisServiceTest`: `depositLetter_high`, `ssrfSenders_noLookupCalls`
- **Verify:** an end-to-end upload → READY in the UI or with curl.

**Checkpoint P6:** at-least-once delivery + idempotent consumers; fan-out with two consumer groups; SSRF; hard overrides.

---

## P7 — AI with Groq (PRD H31–34)

### P7-01 · Groq client and model chain — the fallback is never cut
- **PRD:** §8.2, §8.4 · **Decisions:** D-11, D-30, V-06, V-07 · **Est:** 1.25 h
- **Files:** `ai/GroqClient.java` (`RestClient`, base URL `https://api.groq.com/openai/v1`), `ai/LlmChain.java`, `ai/AiProperties.java` (PRD §15 names: `GROQ_API_KEYS` holds a single key from one Groq org, and `AI_CHAIN=groq:openai/gpt-oss-120b,groq:openai/gpt-oss-20b,groq:qwen/qwen3.8-27b`)
- **Acceptance:**
  - Each model times out at 10 s.
  - On a 429, use `retry-after`, else parse the duration reset headers, else wait 60 s; then mark the model exhausted until then.
  - 5xx, timeout, connection refused, invalid JSON or an unknown model → next model.
  - All models fail → empty. The analysis continues and the report shows "AI unavailable".
  - Request settings: `reasoning_effort` low (gpt-oss) or none (qwen); `include_reasoning: false`; `response_format: json_object`; `max_completion_tokens` ≤ 1,500.
- **Tests:** `LlmChainTest`: `rateLimited_next`, `retryAfterHonoured`, `durationHeaderParsed`, `readTimeout_next`, `connectRefused_next`, `unknownModel_skipped`, `allFail_empty`

### P7-02 · Masking, extraction, cache, prompt-injection defence
- **PRD:** §7, §8.1, §8.5, §8.6, §18 · **Decisions:** D-07, D-06, R-A · **Est:** 1.75 h
- **Files:** `ai/Redactor.java`, `ai/ExtractionPrompt.java`, `ai/ExtractionResult.java` (record), `ai/LlmCache.java`, `analysis/ExtractionMerger.java`
- **Acceptance:**
  - Masking: phones, emails, UPI IDs and greeting/label names become placeholders (mapped back locally).
  - Text: whitespace collapsed, capped at about 6,000 chars.
  - Prompt: the letter sits inside delimiters in the user message, never in the system prompt.
  - Parsing: strict record parsing (unknown fields → rejected).
  - Cache: key = sha256(task + promptVersion + masked text); stores the masked response.
  - Merge: AI only fills gaps and adds candidates (labelled "AI-extracted").
- **Tests:**
  - `RedactorTest` (Indian phones, emails, UPI IDs, "Dear Rahul Sharma,")
  - `ProviderRequestRedactionTest.requestBody_noRawPii` (WireMock-captured request contains no planted markers)
  - `ExtractionResultParsingTest.unknownField_rejected`
  - `LlmCacheTest.secondCall_noProviderCall`
  - `PromptInjectionTest`: hostile but *valid* AI output → the band is unchanged, `score(on) ≥ score(off)`, and the checks triggered with AI on include all of those with AI off
  - `AnalysisServiceTest.aiEmpty_partialAiUsedFalse`

### P7-03 · "Explain simply"
- **PRD:** §4 F4, §8.1, §11 · **Est:** 1.0 h
- **Files:** `ai/ExplainService.java`, `analysis/ExplainController.java`
- **Acceptance:**
  - `POST /api/reports/{id}/explain` is owner-only (404 otherwise).
  - Uses the signals as input (masked), cached in `report.explanation`.
  - If AI is empty, falls back to a template built from the signals.
- **Tests:** `ExplainServiceTest`: `cachedSecondCall`, `aiUnavailable_template`, `notOwner_404`

**Checkpoint P7:** the fallback chain; why AI can't change the score; masking.

---

## P8 — Known companies and lookalikes (PRD H34–35)

### P8-01 · Admin-only company list + seed
- **PRD:** §4 F8, §11 · **Decisions:** D-24, V-21 · **Est:** 0.75 h
- **Files:** `V8__known_company.sql`, `V9__seed_known_companies.sql` (the user-verified list), `company/KnownCompanyController.java`, `KnownCompanyService.java`
- **Acceptance:**
  - `GET/POST/DELETE /api/known-companies` is ADMIN only (others 403).
  - Domains are normalised to lowercase ASCII and are unique.
- **Tests:** `KnownCompanyControllerTest`: `nonAdmin_403`, `duplicateDomain_409`

### P8-02 · C3 (domain mismatch) and C4 (brand lookalike)
- **PRD:** §7.1 C3/C4 · **Decisions:** D-03 · **Est:** 1.25 h
- **Files:** `analysis/checks/CompanyDomainMismatchCheck.java`, `LookalikeDomainCheck.java`, `analysis/lookup/DomainNormalizer.java`
- **Acceptance:**
  - C3 +20: the claimed company is known, and the sender domain isn't one of its official domains.
  - C4 +30: per D-03.
  - PRD done-when: `inf0sys-careers.com` is flagged.
- **Tests:** `LookalikeDomainCheckTest`: `inf0sysCareers_flagged`, `lnfosys_flagged`, `official_notFlagged`, `cyrillicHomoglyph_flagged`, `unrelated_notFlagged`; `CompanyDomainMismatchCheckTest`

---

## P9 — Registry (PRD H48–51)

### P9-01 · Snapshot consumer (fan-out)
- **PRD:** §5.3, §10 · **Decisions:** D-04, D-20 · **Est:** 0.75 h
- **Files:** `V10__registry.sql` (`report_snapshot` + `signals_json`, `indicator`, `indicator_reporter`), `registry/ReportSnapshotListener.java` (group `registry-snapshot`)
- **Acceptance:** upsert by `submission_id`; dedupe; DLT.
- **Tests:** `ReportSnapshotListenerTest`: `upsert_idempotent`, `poison_toDlt`

### P9-02 · HMAC and SimHash
- **PRD:** §10.1, §18 · **Decisions:** D-18 · **Est:** 1.0 h
- **Files:** `registry/IndicatorHasher.java` (HMAC-SHA256 with `INDICATOR_HMAC_PEPPER`; phones normalised to `+91…`; UPI lowercased); `analysis/SimHasher.java` (PRD §10.1 steps 1–5; < 50 words → none; stable 64-bit hash)
- **Tests:**
  - `IndicatorHasherTest`: `phoneFormats_sameHash`, `differentPepper_differentHash`
  - `SimHashTest`: `nearDuplicate_withinThree`, `differentTexts_beyondThree`, `namesNumbersDatesChanged_zeroToOne`, `under50Words_none`, `genuineLettersSharedBoilerplate_beyondThree`

### P9-03 · Matching service (in-process) + shared-domain guard
- **PRD:** §7.1 C10–C12, §10.1 · **Decisions:** D-08, D-33.5 · **Est:** 1.25 h
- **Files:** `registry/MatchService.java`, `registry/IndicatorFactory.java`
- **Acceptance:**
  - Exact matches on domain, phone HMAC and UPI HMAC; SimHash matches with `BIT_COUNT(a ^ b) <= 3`.
  - Unverified count = distinct reporters across all matches.
  - REJECTED markers are ignored.
  - Free-mail and official domains never become markers (D-08) and are ignored when matching.
- **Tests:** `MatchServiceIT` (Testcontainers): `confirmedPhone_matches`, `simhashWithin3`, `distinctReporters_union`, `rejected_ignored`; `IndicatorFactoryTest.freeMailDomain_neverBecomesIndicator`

### P9-04 · C10, C11, C12 + copied-letter guard
- **PRD:** §7.2, §7.3, §18 · **Decisions:** R-A, R-B · **Est:** 1.5 h
- **Files:** `analysis/checks/ConfirmedIndicatorCheck.java`, `NearDuplicateCheck.java`, `UnverifiedReportsCheck.java`
- **Acceptance:**
  - C10 and C11 count only CONFIRMED matches.
  - C11 → High, unless the sender is on the official domain of a company **identified by rules**, in which case +20 with the warning "wording resembles a reported scam".
  - C12: 3+ reporters → +25; 1–2 → +10.
  - The event carries the hmacs and the simhash.
  - PRD done-when: a scam confirmed at user A is flagged for user B.
- **Tests:**
  - `CopiedLetterGuardTest`: `officialSender_plus20NotHigh`, `nonOfficialSender_high`, `aiOnlyCompany_noDowngrade`
  - `NearDuplicateCheckTest.unverifiedMatch_noOverride`
  - `ConfirmedIndicatorCheckTest.unverifiedMatch_noOverride`
  - `UnverifiedReportsCheckTest` (tiers)

**Checkpoint P9:** why HMAC and not SHA-256; how SimHash works; why unverified reports never override.

---

## P10 — Scam reports, reviews, downloads (PRD H51–53)

### P10-01 · Report a scam
- **PRD:** §4 F6, §6.4.1–4, §14.3 · **Decisions:** D-08, D-12, D-33.5 · **Est:** 1.0 h
- **Files:** `V11__scam_report_review.sql` (`scam_report` + `version`, `review_case`), `scamreport/ScamReportController.java`, `ScamReportService.java`
- **Acceptance:**
  - `POST /api/scam-reports {submissionId, description ≤ 2000, amountAsked?}`: the snapshot owner must be the current user (404 otherwise).
  - One report per submission → 409.
  - A 6th report in a day → 429 (DB count).
  - Markers are created as UNVERIFIED (CONFIRMED ones stay CONFIRMED), and the reporter is added once.
- **Tests:** `ScamReportAccessTest.otherUsersSubmission_404`; `ScamReportServiceTest`: `duplicate_409`, `sixthInDay_429`, `confirmedStaysConfirmed`

### P10-02 · Admin moderation and Revert
- **PRD:** §6.4.5–7, §13.8 · **Decisions:** D-13, D-22, D-33.4, R-E · **Est:** 1.25 h
- **Files:** `scamreport/AdminScamReportController.java`, `registry/RevertController.java`
- **Acceptance:**
  - `GET /api/admin/scam-reports?status=OPEN` returns the snapshot evidence, reporter counts and version.
  - `POST …/{id}/decision` with If-Match (409 if stale):
    - confirm → markers CONFIRMED
    - reject → D-33.4
  - `POST /api/admin/indicators/{id}/revert`: CONFIRMED → UNVERIFIED.
  - Every action writes an audit row; the reporter is emailed.
- **Tests:** `ScamReportDecisionTest`: `confirm_upgrades`, `reject_cleanup`, `stale_409`, `nonAdmin_403`, `audited`; `RevertTest.confirmedToUnverified_audited`

### P10-03 · TPO review cases
- **PRD:** §4 F5, §6.5, §13.8 · **Decisions:** D-04, D-08, R-E · **Est:** 1.25 h
- **Files:** `review/ReviewController.java`, `ReviewService.java`
- **Acceptance:**
  - `POST /api/reviews {submissionId}` requires the owner, an APPROVED membership, and the snapshot college = the user's college (404 otherwise). A duplicate → 409.
  - `GET /api/reviews/queue` and `GET /api/reviews/{id}` (case + snapshot evidence) are for the TPO of that college.
  - Decision with If-Match (409 if stale); CONFIRMED_SCAM → markers CONFIRMED, except shared domains.
  - Each decision writes an audit row and emails the student.
- **Tests:**
  - `ReviewDecisionConcurrencyTest.sameVersion_one200one409`
  - `ReviewScopeTest`: `otherCollege_404`, `notOwnerOrNotApproved_404`
  - `ReviewDecisionTest`: `confirmedScam_upgrades`, `sharedDomainExcluded`, `audited`

### P10-04 · PDF download links
- **PRD:** §9.1 · **Decisions:** D-05, M-07 · **Est:** 0.75 h
- **Acceptance:**
  - `GET /api/reviews/{id}/pdf-link` (TPO of the case's college) and `GET /api/admin/scam-reports/{id}/pdf-link` (ADMIN) → `{url, expiresAt}` per M-07.
  - Everyone else gets 404.
- **Tests:** `PdfLinkAccessTest`: `tpoOwnCollege_ok`, `tpoOtherCollege_404`, `student_404`, `admin_ok`

### P10-05 · `GET /api/registry/domains/{domain}`
- **PRD:** §11 · **Est:** 0.25 h
- **Acceptance:** signed in; syntax validated; returns `{status: CONFIRMED|UNVERIFIED|NONE, reporterCount?}`.

**Checkpoint P10:** optimistic locking; trust levels; who can see what.

---

## P11 — 30-day retention

### P11-01 · Daily cleanup job
- **PRD:** F10, §6.3, §14.6 · **Decisions:** D-06 · **Est:** 1.0 h
- **Files:** `retention/RetentionJob.java` (`@Scheduled` daily)
- **Acceptance:** For each submission older than 30 days:
  1. Delete its Cloudinary PDF.
  2. Scrub its text and contact fields, and set the status to DELETED.
  3. Delete its report, signals and explanation.
  4. Delete its snapshot, unless an OPEN scam report or review still references it; in that case it goes 30 days after the decision.

  Also:
  - `llm_cache` rows older than 30 days are deleted.
  - `processed_event` rows older than 14 days are deleted.
  - Indicators and the audit log are kept.
  - A Cloudinary failure is retried on the next run.
- **Tests:** `RetentionJobTest`: `expired_allCleaned`, `snapshotKeptWhileCaseOpen`, `cloudinaryFails_retriedNextRun`, `notExpired_untouched`

---

## P12 — Angular screens (PRD H53–57; 12 screens after D-17/D-24)

### P12-01 · Upload, My submissions
- **Est:** 1.5 h
- **Acceptance:** PDF or text plus the optional fields. The Idempotency-Key is generated once per attempt and reused on retry. 413/415/422/429 are shown inline.
- **Tests:** `upload.spec` (`retry_reusesKey`, `422_shown`)

### P12-02 · Report (polling), Explain, Ask my TPO, Report scam
- **Decisions:** D-14 · **Est:** 2.0 h
- **Acceptance:**
  - `timer` + `exhaustMap` + `takeWhile` on `/api/submissions/{id}`, with a 3-min cap.
  - Renders: band word + shape + colour with the verbatim message; signals with evidence, source kind and "AI-extracted"; "Reported as a scam by N users (not verified)" kept distinct from "Confirmed scam"; PARTIAL notices naming the missing checks.
  - "Ask my TPO" appears only for students on campus.
- **Tests:** `report.spec`: `stopsOnReady`, `stopsOnPartial`, `stopsOnFailed`, `waitsForPrevious`, `trustLevelsDistinct`, `askTpo_hiddenIfIndependent`

### P12-03 · TPO: pending members, review queue (evidence + PDF link)
- **Est:** 1.25 h
- **Acceptance:** the `If-Match` 409 is handled ("Someone else decided — refreshed"); a reject needs a reason.

### P12-04 · Admin: colleges and TPOs, unverified scam reports (+ Revert, PDF link), known companies
- **Est:** 1.75 h
- **Acceptance:** the same 409 handling; unverified data is never shown as fact.
- **Verify (P12):** `npx ng test --watch=false && npx ng build`; manual journeys J1–J6 ([05 §5](05-testing-and-security-plan.md)) at 360 px and on desktop.

---

## P13 — Test and security gate (PRD H57–59)

### P13-01 · All mandatory tests green
- **Est:** 1.0 h
- **Acceptance:** every test in [05 §2](05-testing-and-security-plan.md) exists and passes; `mvn verify` and the frontend tests are green; results logged in 05 §7, with executed and passed kept separate.

### P13-02 · Security proofs
- **Est:** 1.0 h
- **Acceptance:** S-01…S-12 and drills F-01…F-06 ([05](05-testing-and-security-plan.md)) are run, with evidence recorded.

---

## P14 — Deploy on Oracle Cloud (PRD H59–62)

### P14-01 · Production compose and Caddy
- **Decisions:** D-23, M-09 · **Est:** 0.75 h
- **Acceptance:**
  - `docker-compose.prod.yml` sets the memory limits, log rotation, `restart: unless-stopped`, and Caddy on 80/443 with site `{$APP_DOMAIN}`.
  - Every image has arm64 (`docker manifest inspect`).

### P14-02 · VM, firewall, DuckDNS, first start
- **Est:** 2.0 h (1–4)
- **Acceptance:** the [06 §4](06-deployment-and-verification.md) runbook is complete, and `https://<name>.duckdns.org` works.

### P14-03 · Google production settings, demo data, smoke test
- **Est:** 0.75 h
- **Acceptance:**
  - Redirect URI added; consent screen set to "In production".
  - Demo registry seeded with **your own fake letters** (§19.5).
  - A friend signs in from a phone and gets a report.

## P15 — README (PRD H62–63)

### P15-01 · README
- **Est:** 1.0 h
- **Acceptance:**
  - The architecture diagram (02 §1); how to run; the env variable names; the PRD §19 limitations (with D-16, D-21 and D-11 noted); the real AI capacity; the off-campus claim marked as an assumption.
  - No resume numbers unless measured.

---

## Summary

| Phase | Tasks | Est (P50 h) |
|---|---|---|
| P1 | 3 | 3.5 |
| P2 | 3 | 4.0 |
| P3 | 2 | 2.5 |
| P4 | 2 | 3.0 |
| P5 | 5 | 5.0 |
| P6 | 7 | 7.0 |
| P7 | 3 | 4.0 |
| P8 | 2 | 2.0 |
| P9 | 4 | 4.5 |
| P10 | 5 | 4.5 |
| P11 | 1 | 1.0 |
| P12 | 4 | 6.5 |
| P13 | 2 | 2.0 |
| P14 | 3 | 3.5 |
| P15 | 1 | 1.0 |
| **Total** | **47** (+2 user tasks in P0) | **≈ 54 h** (+ ≈ 3.5 h checkpoints) |
