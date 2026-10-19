# 07 — Phase Execution Prompts (copy-ready)

Run **one prompt per Claude Code session**, in order, and only after the previous milestone has passed. Each prompt relies on [CLAUDE.md](../../CLAUDE.md) (auto-loaded) and §0 below.

---

## §0 Standard protocol (every phase)

1. **Preconditions.** Check the prompt's preconditions. If any isn't met, stop and say so.
2. **Ask first.** Ask the user this phase's **M-items** ([01 §3](01-prd-review-and-open-decisions.md)) with the recommended option and an alternative, and wait. Record each answer in 01 §3. Also ask about anything not covered by D-01…D-33 instead of deciding it yourself.
3. **Branch.** Work on `phase/pNN-<slug>`, never on `main`. Commit after each green task.
4. **Plan.** Use `superpowers:writing-plans` to expand **only this phase's tasks** from [04](04-work-breakdown.md) into `docs/superpowers/plans/<date>-pNN-<slug>.md`.
5. **Build with TDD.** Use `superpowers:executing-plans` (or `subagent-driven-development`), with `superpowers:test-driven-development` per task. Load only the listed skills. Where a `spring-boot-3-skills` skill conflicts with Spring Boot 4.1, the official Boot 4 docs win.
6. **Verify.** Run the listed commands. Record them in [05 §7](05-testing-and-security-plan.md) with **Executed** and **Passed** kept separate. Use `superpowers:verification-before-completion` before saying "done".
7. **Stop** (report and wait) if:
   - a build or test still fails after 2 focused fixes (use `superpowers:systematic-debugging`)
   - something needs a decision that isn't in 01
   - the work goes outside the phase's scope
   - the phase takes more than 2× its estimate
   - a secret is needed (ask the user to put it in `.env`; never print it)
8. **Finish.** Tick the tasks in 04, report the files changed and the test results, ask the checkpoint questions ([03 §5](03-implementation-roadmap.md)), and **wait for approval**.

---

## P0 — Your checklist before P1 (no prompt needed)

- [ ] `.env` created from [06 §2](06-deployment-and-verification.md) (git-ignored)
- [ ] Google OAuth client with redirect `http://localhost/login/oauth2/code/google`
- [ ] Docker Desktop running (Testcontainers needs it)
- [ ] Groq account + key (before P7) · Cloudinary account (before P5) · dedicated Gmail + app password (before P3)
- [ ] ~10 known companies with official domains verified (before P8)

## P1 — Foundation (→ M1)

```text
Phase P1 — Foundation. Follow docs/plan/07-phase-execution-prompts.md §0.
Preconditions: decisions D-01…D-33 approved (docs/plan/01 §1); .env exists and is git-ignored; Docker running.
Ask first: M-01 (repo layout), M-02 (package names), M-03 (Caddy locally).
Scope: tasks P1-01, P1-02, P1-03 in docs/plan/04-work-breakdown.md only.
Read: PRD §5.5, §9, §14.6, §14.7, §15; docs/plan/02 §1, §2, §4, §10; decisions D-01, D-16, D-23, D-27, D-32.
Skills: spring-boot-3-skills:layered-architecture, spring-boot-3-skills:flyway-migrations, spring-boot-3-skills:problem-details-rfc9457, spring-boot-3-skills:configuration-properties.
Tests first: LoggingAspectTest (logsMethodAndDuration, neverLogsArguments), ProblemDetailExceptionHandlerTest.
Verify: backend/mvnw -B -f backend/pom.xml verify; docker compose up -d --build; docker compose ps (all healthy); docker compose port app 8080 prints nothing; Flyway "Successfully applied" on mysql:8.4; grep requestId logs/offershield.log; git check-ignore .env.
Stop if: Flyway fails on MySQL 8.4 (D-32: stop and ask).
Done when: milestone M1 in docs/plan/03 §2 — every item evidenced in docs/plan/05 §7.
```

## P2 — Login and security (→ M2)

```text
Phase P2 — Google login, session, CSRF, headers, rate limit. Follow docs/plan/07 §0.
Preconditions: M1 passed; GOOGLE_CLIENT_ID/SECRET, ADMIN_EMAILS, APP_BASE_URL in .env.
Ask first: M-04 (session lifetime).
Scope: P2-01, P2-02, P2-03 only.
Read: PRD §3, §6.1, §12 (XSRF), §13.6, §14.1, §14.2, §14.7; docs/plan/02 §6; decisions D-02, D-10, D-12, D-21; rules R-C, R-D, R-E.
Skills: spring-boot-3-skills:problem-details-rfc9457, spring-boot-3-skills:configuration-properties, spring-boot-3-skills:testing-pyramid. Use the official Spring Security 7 reference for oauth2Login and csrf(c -> c.spa()).
Tests first: OidcUserProvisioningServiceTest, LoginRedirectTest, SessionCookieTest, ApiUnauthenticatedTest, CsrfTest, SecurityHeadersTest, UserRateLimitFilterTest.
Verify: backend/mvnw -B -f backend/pom.xml verify; sign in at http://localhost/oauth2/authorization/google; /api/auth/me shows your role; proofs S-01, S-02, S-04, S-08, S-09 recorded.
Stop if: PKCE/nonce missing from the redirect; an unverified email gets ADMIN/TPO.
```

## P3 — Colleges and membership

```text
Phase P3 — Colleges, TPO assignment, manual join, email. Follow docs/plan/07 §0.
Preconditions: M2 passed; SMTP_USERNAME/SMTP_APP_PASSWORD in .env.
Scope: P3-01, P3-02 only (manual join only — D-17).
Read: PRD §3, §3.1 (path C), §4 F2, F9, §11 (identity rows), §13.8; decisions D-02, D-17, D-22, D-33.3, D-33.7; rule R-E.
Skills: spring-boot-3-skills:layered-architecture, spring-boot-3-skills:transactional-patterns, spring-boot-3-skills:flyway-migrations, spring-boot-3-skills:problem-details-rfc9457.
Tests first: CollegeAdminControllerTest, MembershipDecisionTest, EmailServiceTest.
Verify: backend/mvnw -B -f backend/pom.xml verify; journey J2 with curl (approve with If-Match; stale → 409); email received.
```

## P4 — Angular shell (→ M3)

```text
Phase P4 — Angular shell. Follow docs/plan/07 §0.
Preconditions: P3 verified.
Ask first: M-05 (nginx container or Caddy file_server).
Scope: P4-01, P4-02 only (Login, Choose path, Join college).
Read: PRD §3.1, §12; docs/plan/02 §9; decisions D-10, D-14, D-26.
Skills: angular-new-app (create frontend/ with Angular 22), angular-developer, frontend-design:frontend-design, web-design-guidelines (review at the end).
Constraints: standalone + signals + @if/@for; lazy /app /tpo /admin with canMatch; one interceptor; relative /api URLs; no [innerHTML]; inlineCritical false; self-hosted fonts.
Tests first: auth.guard.spec, role.guard.spec, unauthorized.interceptor.spec, join-college.spec.
Verify: cd frontend && npm ci && npx ng test --watch=false && npx ng build; grep -r "innerHTML" frontend/src returns nothing; click-through at http://localhost.
Done when: milestone M3.
```

## P5 — Submission

```text
Phase P5 — Upload, validation, encryption, Cloudinary, Kafka publish. Follow docs/plan/07 §0.
Preconditions: M3 passed; CLOUDINARY_URL and TEXT_AES_KEY_BASE64 in .env.
Scope: P5-01 … P5-05 only.
Read: PRD §4 F3, §6.2, §6.3, §9.1, §10 (submission), §10.1 (AES-GCM), §13.3, §13.4, §14.4; docs/plan/02 §3, §5; decisions D-05, D-12, D-20, D-28, D-33.1, D-33.2, D-33.6.
Skills: spring-boot-3-skills:idempotency-patterns, spring-boot-3-skills:transactional-patterns, spring-boot-3-skills:event-driven-messaging, spring-boot-3-skills:flyway-migrations, spring-boot-3-skills:testing-pyramid.
Tests first: IdempotencyKeyTest, UploadLimitTest, SubmissionAccessTest, FileValidatorTest, TextCipherTest, CloudinaryStoreTest, SubmissionServiceTest, SubmissionEventPublisherTest, QueuedSubmissionSweeperTest.
Verify: backend/mvnw -B -f backend/pom.xml verify; upload via http://localhost → 202; the event is visible on submission.created (no letter text); V-10 Cloudinary check recorded; proofs S-03 (submission part), S-06, S-12.
Stop if: letter text appears in Kafka, logs or unencrypted in the DB.
```

## P6 — Analysis core (→ M4)

```text
Phase P6 — Kafka consumer, checks, RDAP/DNS, scoring, report, fan-out. Follow docs/plan/07 §0.
Preconditions: P5 verified.
Ask first: M-06 (registrable-domain helper).
Scope: P6-01 … P6-07 only (no AI, no registry matching yet).
Read: PRD §2.1, §6.2, §6.3, §7, §7.1 (C1, C2, C5, C6, C8, C9), §7.2, §10 (report/signal), §13.4, §13.5, §13.7, §14.5; docs/plan/02 §5, §8; decisions D-09, D-14, D-19, D-20, D-28, D-31.
Skills: spring-boot-3-skills:event-driven-messaging, spring-boot-3-skills:idempotency-patterns, spring-boot-3-skills:http-interface-clients, spring-boot-3-skills:testing-pyramid.
Tests first: SubmissionCreatedListenerTest, MoneyRequestCheckTest, FreeMailSenderCheckTest, OffPlatformContactCheckTest, PressureLanguageCheckTest, DomainValidatorTest, RdapClientTest, YoungDomainCheckTest, MailSetupCheckTest, ScoringEngineTest, SubmissionStatusListenerTest, ReportAccessTest, AnalysisServiceTest.
Verify: backend/mvnw -B -f backend/pom.xml verify; the deposit-letter fixture → High with C1 evidence; status READY through analysis.completed; proof S-05; drill F-05.
Done when: milestone M4.
```

## P7 — AI with Groq

```text
Phase P7 — Groq chain, masking, extraction, cache, Explain simply. Follow docs/plan/07 §0.
Preconditions: M4 passed; GROQ_API_KEYS and AI_CHAIN in .env (or it's recorded that Groq doesn't work from India — then build and test with stubs only).
Scope: P7-01, P7-02, P7-03 (P7-03 is cut #1 only if I say so).
Read: PRD §7 (AI never creates evidence), §8 (all), §15; decisions D-06, D-07, D-11, D-30; rule R-A; verification V-06, V-07, V-09.
Skills: spring-boot-3-skills:http-interface-clients, spring-boot-3-skills:testing-pyramid.
Tests first: LlmChainTest, RedactorTest, ProviderRequestRedactionTest, ExtractionResultParsingTest, LlmCacheTest, PromptInjectionTest, AnalysisServiceTest.aiEmpty_partialAiUsedFalse, ExplainServiceTest.
Verify: backend/mvnw -B -f backend/pom.xml verify; a real Groq call fills AI-extracted fields; drill F-01 (bad key → "AI unavailable"); measure the tokens used per call and record them (closes V-09).
```

## P8 — Known companies and lookalikes (→ M5)

```text
Phase P8 — Admin company list + C3/C4. Follow docs/plan/07 §0.
Preconditions: P7 verified; your verified seed list is recorded in docs/plan/01 V-21.
Scope: P8-01, P8-02.
Read: PRD §4 F8, §7.1 C3/C4, §11 (known-companies row); decisions D-03, D-24.
Skills: spring-boot-3-skills:layered-architecture, spring-boot-3-skills:flyway-migrations.
Tests first: KnownCompanyControllerTest, LookalikeDomainCheckTest, CompanyDomainMismatchCheckTest.
Verify: backend/mvnw -B -f backend/pom.xml verify; the inf0sys-careers.com fixture is flagged; journey J6.
Done when: milestone M5.
```

## P9 — Registry

```text
Phase P9 — Snapshot consumer, HMAC, SimHash, matching, C10–C12. Follow docs/plan/07 §0.
Preconditions: M5 passed; INDICATOR_HMAC_PEPPER in .env.
Scope: P9-01 … P9-04.
Read: PRD §5.3, §7.1 C10–C12, §7.2, §7.3, §10 (registry tables), §10.1 (HMAC, SimHash); docs/plan/02 §5, §7; decisions D-08, D-18, D-20, D-33.5; rules R-A, R-B.
Skills: spring-boot-3-skills:event-driven-messaging, spring-boot-3-skills:flyway-migrations, spring-boot-3-skills:testing-pyramid.
Tests first: ReportSnapshotListenerTest, IndicatorHasherTest, SimHashTest, MatchServiceIT, IndicatorFactoryTest, CopiedLetterGuardTest, NearDuplicateCheckTest, ConfirmedIndicatorCheckTest, UnverifiedReportsCheckTest.
Verify: backend/mvnw -B -f backend/pom.xml verify; a scam confirmed (via test data) at user A is flagged for user B.
```

## P10 — Scam reports, reviews, PDF links (→ M6)

```text
Phase P10 — Report scam, admin moderation + Revert, TPO reviews, PDF links, domain lookup. Follow docs/plan/07 §0.
Preconditions: P9 verified.
Ask first: M-07 (PDF link lifetime).
Scope: P10-01 … P10-05.
Read: PRD §4 F5, F6, F9, §6.4, §6.5, §9.1, §11 (registry rows), §13.8, §14.3, §14.6; docs/plan/02 §7; decisions D-04, D-05, D-08, D-12, D-13, D-22, D-33.4, D-33.5, D-33.7; rule R-E.
Skills: spring-boot-3-skills:transactional-patterns, spring-boot-3-skills:problem-details-rfc9457, spring-boot-3-skills:flyway-migrations.
Tests first: ScamReportAccessTest, ScamReportServiceTest, ScamReportDecisionTest, RevertTest, ReviewDecisionConcurrencyTest, ReviewScopeTest, ReviewDecisionTest, PdfLinkAccessTest.
Verify: backend/mvnw -B -f backend/pom.xml verify; journeys J3, J4, J5 with curl; emails received; drill F-06; proof S-03 (report + PDF link parts), S-07.
Done when: milestone M6.
```

## P11 — Retention

```text
Phase P11 — 30-day cleanup job. Follow docs/plan/07 §0.
Preconditions: M6 passed.
Scope: P11-01.
Read: PRD F10, §6.3, §9.1, §14.6; decision D-06.
Skills: spring-boot-3-skills:transactional-patterns.
Tests first: RetentionJobTest.
Verify: backend/mvnw -B -f backend/pom.xml verify; run the job against rows with expires_at set in the past (test profile) and confirm the Cloudinary file is gone.
```

## P12 — Angular screens

```text
Phase P12 — Student, TPO and admin screens (12 screens). Follow docs/plan/07 §0.
Preconditions: P11 verified.
Ask first: M-08 (UI language).
Scope: P12-01 … P12-04 (PRD cut #2 only if I say so).
Read: PRD §2.1, §4 F3–F8, §7.2 (exact band messages), §12; docs/plan/02 §9; decisions D-04, D-05, D-13, D-14, D-26.
Skills: angular-developer, frontend-design:frontend-design, web-design-guidelines (review the changed templates before finishing).
Constraints: poll /api/submissions/{id} with exhaustMap and a 3-min cap; one aria-live region; band = word + shape + colour; unverified vs confirmed kept distinct; never "verified"/"safe"; If-Match + 409 handling; no [innerHTML].
Tests first: upload.spec, report.spec, plus 409 specs for the review queue and scam reports.
Verify: npx ng test --watch=false && npx ng build; journeys J1–J6 at 360 px and on desktop.
```

## P13 — Test and security gate (→ M7)

```text
Phase P13 — Verification gate. Follow docs/plan/07 §0. No new features.
Preconditions: P12 verified.
Scope: P13-01, P13-02; fix only defects found (each with a failing test first).
Read: docs/plan/05 (all); PRD §14, §18.
Skills/agents: superpowers:verification-before-completion; run /security-review on the branch; run the pr-review-toolkit agents code-reviewer, silent-failure-hunter and pr-test-analyzer on the diff from main; handle findings with superpowers:receiving-code-review.
Verify: backend/mvnw -B -f backend/pom.xml verify; frontend tests + build; every test in 05 §2 present and passing; S-01…S-12 and F-01…F-06 run, with evidence in 05 §7.
Done when: milestone M7. Do not deploy with any mandatory test failing.
```

## P14 — Deploy on Oracle Cloud

```text
Phase P14 — OCI deployment. Follow docs/plan/07 §0.
Preconditions: M7 passed; OCI VM reachable by SSH; DuckDNS name created; production .env prepared on the VM.
Ask first: M-09 (memory limits), M-10 (Render backup runbook or not).
Scope: P14-01 … P14-03. Files: docker-compose.prod.yml and Caddyfile changes; everything else is runbook steps that I run or approve one by one.
Read: PRD §16; docs/plan/06 §2, §4, §5, §7; decision D-23.
Skills: spring-boot-3-skills:container-native-deployment (JVM/container flags only).
Rules: show me each remote command before running it; never print .env; don't enable UFW.
Verify: docs/plan/06 §7 checklist (all 6 steps).
Done when: a friend signs in from a phone and gets a report.
```

## P15 — README (→ M8)

```text
Phase P15 — README. Follow docs/plan/07 §0.
Preconditions: P14 verified.
Scope: P15-01 only.
Include: the architecture diagram (docs/plan/02 §1), how to run (docs/plan/06 §3), env variable names only, PRD §19 limitations 1–11 (adjusted for D-11, D-16, D-21), the real AI capacity, the off-campus claim labelled as an assumption, and "no backups in v1" (K-08). No resume numbers unless measured.
Verify: links work; gitleaks clean; push only after I confirm.
Done when: milestone M8.
```
