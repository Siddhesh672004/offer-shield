# OfferShield — project instructions for Claude Code

OfferShield lets students upload a suspicious job offer (PDF or text) and get a **rule-based, evidence-backed risk report**. On-campus students can ask their TPO to review it. Anyone can report a scam, and an admin confirms reports into a shared registry.

It is built as **one Spring Boot app plus Kafka, MySQL and Angular**, behind Caddy, on a free Oracle Cloud VM.

## Sources of truth and precedence

1. **Approved decisions:** [`docs/plan/01-prd-review-and-open-decisions.md`](docs/plan/01-prd-review-and-open-decisions.md) §1 (D-01…D-33, approved 2026-10-19).
2. **Product requirements:** [`OfferShield_PRD.md`](OfferShield_PRD.md). **Never edit it.** Cite sections as "PRD §7.2".
3. **Plan:** `docs/plan/00`–`07`:
   - `00` coverage matrix
   - `02` architecture
   - `03` roadmap
   - `04` tasks
   - `05` tests and security
   - `06` deployment
   - `07` phase prompts

A decision overrides the PRD text where they differ.

## Working with the user (mandatory)

- **Decide autonomously (since 2026-10-19).** Planning is over: don't ask the user for permission or decisions. Take the recommended M-item defaults (01 §3) and sensible production-grade choices, record each one in 01 §3 / 05 §7, and proceed.
- Use **free services only**: no paid plans and no card-only trials.
- Keep it **basic to medium**. Prefer the simplest production-grade solution the user can understand and explain.
- Version upgrades are pre-approved. Never remove a feature or cut a security invariant.

## Status

Planning is complete. Implementation runs **one phase at a time** using the prompts in `docs/plan/07`. P1–P4 are merged to `main`; P5–P13, P14-01 (prod compose + guide) and P15-01 (README) are done on `phase/p05-p13` (uncommitted, 2026-11-18). Secrets are in `.env`; the live checks ran on 2026-11-18 (05 §7). Next: owner's go-ahead to commit/push, then P14-02/03 on the owner's Oracle VM (`docs/deploy/oci-step-by-step.md`), first real admin sign-in, add `docs/deploy/known-companies.json`.

## Stack (D-01; verified 2026-10-19)

| Layer | Choice |
|---|---|
| Backend | Java 21 · Spring Boot **4.1.x** (Security 7, Spring Kafka 4) · Flyway (`spring-boot-starter-flyway` + `flyway-mysql`) · Caffeine · Bucket4j |
| Backend libraries | PDFBox 3.0.x · tika-core · dnsjava · commons-text · `cloudinary-http5` · Spring Mail |
| Infrastructure | `mysql:8.4` · `apache/kafka:4.3.1` (KRaft) · Caddy · nginx. All arm64; **no Bitnami images** |
| Frontend | Angular **22.2.x**, Node 24 LTS: standalone components, signals, zoneless, Vitest |
| Not used (by decision) | API gateway, Redis, Resilience4j, your own JWT, Cerebras, roster CSV, domain auto-join |

Where a `spring-boot-3-skills:*` skill conflicts with Boot 4 APIs, **the official Boot 4 docs win.**

## Layout (M-01/M-02 confirmed at P1)

```
backend/   one Maven module, com.offershield.{auth, college, membership, submission, analysis, ai,
           company, registry, review, scamreport, notification, retention, common}
frontend/  Angular 22
docker-compose.yml · docker-compose.prod.yml · Caddyfile · .env.example · logs/ (git-ignored)
```

- Each feature owns its tables and exposes a service; other features call that service, never its repository.
- Inside a feature, the layers are controller → service → repository. Use DTO records, and never return entities.

## Security invariants (never violate, never cut — D-25)

1. **Identity comes from the Spring Security session only** (Google sign-in, users keyed on `sub`). Never trust identity from request headers.
2. **Only Caddy publishes ports.** The app, MySQL and Kafka stay internal.
3. **IDOR:** scoped queries (`findByIdAndOwnerUserId`, `findByIdAndCollegeId`). Other users' data → **404**; a wrong role → 403.
4. **CSRF:** `csrf(c -> c.spa())` with the `XSRF-TOKEN` cookie. The session cookie is HttpOnly; Secure; SameSite=Lax. Angular uses relative `/api` URLs only.
5. **SSRF:** only a validated domain reaches RDAP or DNS. Reject IP literals, ports, paths, schemes and `localhost`. RDAP hosts come only from the IANA bootstrap. **Never fetch a URL found in a letter.**
6. **Uploads:**
   - ≤ 5 MB, ≤ 10 pages; Tika must detect `application/pdf`.
   - PDFBox with a memory limit and a 10 s timeout; extract text only.
   - Cloudinary upload only after validation, as a **private** file.
   - PDF download links are short-lived, and only for the case's TPO or the admin (D-05).
7. **Crypto:**
   - Letter text: AES-256-GCM (random 12-byte IV, `key_version`, AAD = submissionId).
   - Phones and UPI IDs: HMAC-SHA256 with the pepper.
   - Secrets live only in the git-ignored `.env`. Never print, commit or invent them.
8. **AI (Groq only):**
   - It extracts and explains; it never scores.
   - Mask phones, emails, UPI IDs and greeting names first (D-07).
   - The letter goes in the user message, inside delimiters.
   - Parse the output strictly into a record.
   - AI fields may only **add** risk (R-A).
   - If all models fail, continue with "AI unavailable".
9. **Logging (D-27):** the AOP aspect logs class.method, user and submission IDs, duration, errors and a request ID. **Never arguments, return values, letter text, emails, phones, UPI IDs or tokens.** Actuator exposes `health` only.
10. **Fairness:**
    - Unverified reports never override and are never shown as fact.
    - Free-mail and official company domains never become scam markers (D-08).
    - A "no fee is charged" sentence never triggers C1 (D-09).
11. **Never cut:** SSRF guard, IDOR scoping, file validation, AI fallback-to-none, every PRD §14 control, the PRD §18 tests. The cut list in 03 §4 may be applied only when the user says so.

## Product and copy rules

- Use the PRD §7.2 band messages **verbatim**. Never say "verified", "safe", "legit" or "guaranteed".
- Use "Reported as a scam by N users (not verified)" and "Confirmed scam", visually distinct.
- Every signal shows its evidence and its source kind (1–5). AI-derived fields are labelled "AI-extracted".
- The off-campus risk claim is an assumption (PRD §1.1).
- Test data is **fake letters only**.

## Engineering conventions

- **TDD:** JUnit 5 + AssertJ + Mockito, with names like `method_condition_expected`. MySQL Testcontainers for DB tests; **no H2**.
- **Errors:** RFC 9457 `ProblemDetail`. Decisions use `If-Match: <version>`, and a stale version → 409.
- **Kafka (D-20, D-28):**
  - Topics `submission.created` and `analysis.completed`, keyed by submissionId.
  - Publish after commit and confirm the send.
  - Sweeper backoff 2/4/8/16 min, then FAILED.
  - Dedupe with `processed_event(consumer, event_id)`.
  - 3 retries → `<topic>.DLT`, with the name set explicitly.
  - Re-send `analysis.completed` on a duplicate.
- **Timeouts:** Groq 10 s, RDAP 5 s, DNS 3 s, Cloudinary 15 s, SMTP 10 s. A failed lookup → `UNAVAILABLE` → PARTIAL. Cache with Caffeine.
- **Flyway:** `V{n}__desc.sql`, one DDL change per file, never edit an applied migration.

## Phase-execution rules

1. One phase per session, using its prompt from `docs/plan/07`. Ask its M-items first.
2. Work on a `phase/pNN-*` branch, never on `main`. **Since 2026-10-20: do not commit or push** — keep changes uncommitted on the local branch (`phase/p05-p13`) until the user says otherwise; take local snapshots instead.
3. Plan with `superpowers:writing-plans`; build with `superpowers:executing-plans` or `subagent-driven-development`, plus `superpowers:test-driven-development`.
4. **Stop and report** when:
   - a build or test still fails after 2 focused fixes (then use `superpowers:systematic-debugging`)
   - a decision is missing
   - the work goes out of scope
   - the phase exceeds 2× its estimate
   - a secret is needed
5. **Evidence before claims.** Record every check in `docs/plan/05 §7`, with "executed" and "passed" kept separate.
6. End each phase with: files changed, test results, checkpoint questions (`03 §5`). Then wait for approval.

## Skills by area (load only what the phase prompt lists)

| Area | Skills |
|---|---|
| Backend | `spring-boot-3-skills:{layered-architecture, flyway-migrations, problem-details-rfc9457, configuration-properties, transactional-patterns, idempotency-patterns, event-driven-messaging, http-interface-clients, testing-pyramid, container-native-deployment}` |
| Frontend | `angular-new-app` (P4 only), `angular-developer`, `frontend-design:frontend-design`, `web-design-guidelines` |
| Process | `superpowers:*` |
| Review | `pr-review-toolkit` agents, `/security-review` |

## Commands (once code exists)

```bash
backend/mvnw -B -f backend/pom.xml verify
docker compose up -d --build && docker compose ps
cd frontend && npm ci && npx ng test --watch=false && npx ng build
```
