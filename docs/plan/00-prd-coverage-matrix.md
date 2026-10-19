# 00 — PRD Coverage Matrix

**Source:** [`OfferShield_PRD.md`](../../OfferShield_PRD.md) v2.0, all 1,021 lines (§0–§22) read on 2026-10-19. **Amended by the approved decisions** D-01…D-33 ([01 §1](01-prd-review-and-open-decisions.md)).
**Purpose:** every PRD requirement has a destination — a task in [04](04-work-breakdown.md), a plan document, or an explicit removal by decision.

**Status values:**
- **Planned:** a task builds it as the PRD says.
- **Changed (D-xx):** built, but differently, by approved decision.
- **Removed (D-xx):** cut by approved decision.
- **Excluded:** out of scope per PRD §4.1.
- **Context:** README or interview material, no build work.
- **Ask (M-xx):** a minor item asked at the start of its phase.

---

## §0–§2 Changes, evidence, vision

| ID | PRD § | Requirement | Destination | Status |
|---|---|---|---|---|
| R0-1 | §0.1 | Anyone signs in with Google; college optional; TPO approves joins | P2-02, P3-02 | Planned |
| R0-2 | §0.2 | Off-campus automated report + scam reports, unverified until the admin confirms | P6, P10-01, P10-02 | Planned |
| R0-3 | §0.3 | AI in v1: Groq → Cerebras → none | P7 | Changed (D-11: Groq only) |
| R0-4 | §0.4 | 4 microservices + gateway | 02 | Changed (D-16: one app + Kafka) |
| R0-5 | §0.5 | OCI primary, Render backup, Docker + env | P14; 06 | Planned (Render: M-10) |
| R0-6 | §0.6 | Cloudinary PDFs, Gmail SMTP | P5-04, P3-02 | Planned |
| R0-7 | §0.7 | 72-hour plan | 03 | Changed (D-15: ~1 week) |
| R0-8 | §0 | Truth labels; re-verify facts | 01 §6 | Planned |
| R0-9 | §0 | "Must understand now / later"; Groq ≠ Grok | 03 §5, CLAUDE.md | Planned |
| R1-1 | §1.1 | Evidence statistics | P15-01 | Context |
| R1-2 | §1.1 | The off-campus claim is an assumption | P15-01, CLAUDE.md | Context (guard) |
| R1-3 | §1.2 | Competitor search | 01 §7 | Done |
| R2-1 | §2.1.1 | Every point has a checkable source; the report shows the source kind | P6-07, P12-02 | Planned |
| R2-2 | §2.1.2 | Java scores; AI only extracts/explains; never lowers a score | P6-06, P7-02, P9-04 | Planned (+R-A) |
| R2-3 | §2.1.3 | Unverified shown separately from confirmed | P9-04, P12-02 | Planned |
| R2-4 | §2.1.4 | Never breaks: missing AI/lookups → PARTIAL with the check named | P6-04, P6-05, P7-01 | Planned |

## §3 Users, roles, joining a college

| ID | PRD § | Requirement | Destination | Status |
|---|---|---|---|---|
| R3-1 | §3 | USER: submit, see own reports, report scams, request to join | P2-02, P5, P10-01, P3-02 | Planned |
| R3-2 | §3 | On-campus USER: Ask my TPO | P10-03 | Planned |
| R3-3 | §3 | TPO pre-assigned by email; approves members; reviews; manages known companies | P3-01, P3-02, P10-03 | Changed (D-02 table; D-24: no TPO company list) |
| R3-4 | §3 | ADMIN from env: colleges, TPOs, moderation, global companies | P2-02, P3-01, P10-02, P8-01 | Planned |
| R3-5 | §3 | Roles only from the DB, never from the browser | P2-02 | Planned |
| R3-6 | §3.1 A | Domain auto-join (`hd` claim) | — | Removed (D-17) |
| R3-7 | §3.1 B | Roster CSV + fuzzy match | — | Removed (D-17) |
| R3-8 | §3.1 C | Manual request → TPO approve/reject (reason) → email | P3-02 | Planned |
| R3-9 | §3.1 | Verify the `hd` claim server-side | — | Removed with path A (D-17); `email_verified` rule kept (R-C) |
| R3-10 | §3.1 | No ID-card photos | CLAUDE.md | By design |
| R3-11 | §3.1 | Roll number + Levenshtein ≥ 0.8 | — | Removed (D-17) |

## §4 Features F1–F10 and exclusions

| ID | Requirement | Completion condition (details in 04) | Tasks | Status |
|---|---|---|---|---|
| F1 | Google sign-in, user keyed on `sub`, session | Login at `http://localhost`; `/api/auth/me` shows the role; cookie flags proven | P2-01..03 | Changed (D-21: Spring session cookie, no JWT) |
| F2 | College membership + TPO pending screen (+ CSV) | Join → TPO approves → email; 409 on a stale decision | P3-01, P3-02, P12-03 | Changed (D-17: manual only) |
| F3 | Upload PDF/text + optional contacts; private Cloudinary; async; Idempotency-Key | 202; replay = same ID; event on Kafka; private PDF | P5-01..05, P12-01 | Planned |
| F4 | Checks + AI extraction → score, band, evidence; cached "Explain simply" | Deposit letter → High with evidence; explanation cached | P6, P7, P8-02, P9-04, P12-02 | Planned |
| F5 | Ask my TPO with 3 decisions, optimistic locking, email | Concurrent decisions → one 409; student emailed | P10-03, P12-03 | Planned |
| F6 | Report a scam; distinct reporters; admin confirm/reject | "Reported by 1 user (not verified)" → confirm → High | P10-01, P10-02, P12-02, P12-04 | Planned (+D-13 Revert) |
| F7 | Registry: domains, HMAC phones/UPIs, SimHash | Confirmed at user A → flagged for user B | P9-01..04 | Planned (D-18 keeps SimHash; D-08 guard) |
| F8 | Known-company domains (TPO + admin) → lookalikes | `inf0sys-careers.com` flagged | P8-01, P8-02, P12-04 | Changed (D-24 admin-only; D-03 rule) |
| F9 | Emails: membership, review, scam-report decisions | All 3 delivered | P3-02, P10-02, P10-03 | Changed (D-22: direct send, no topic) |
| F10 | Rate limits, headers, SSRF, AES-GCM, 30-day deletion, health checks | See §14 rows | P2-03, P6-03, P5-03, P11-01, P1-02 | Changed (D-12 limits; D-06 retention) |
| X-1…X-9 | §4.1 out of scope: outbox, SSE, C7, LSH, full Testcontainers, OTP, Eureka, vector DB, WhatsApp/mobile/ML | — | Excluded |

## §5 Architecture

| ID | PRD § | Requirement | Destination | Status |
|---|---|---|---|---|
| R5-1 | §5.1 | Topology with gateway, 4 services, Redis | 02 §1 | Changed (D-16) |
| R5-2 | §5.2 | Gateway owns rate-limit state in Redis | — | Removed (D-16, D-12) |
| R5-3 | §5.2–5.3 | Identity sends all emails; events carry user IDs | P3-02 (`notification`) | Changed (D-22) |
| R5-4 | §5.2 | Submission publishes `submission.created` and consumes `analysis.completed` | P5-05, P6-07 | Planned |
| R5-5 | §5.2 | Analysis consumes `submission.created` and publishes `analysis.completed`; calls registry, RDAP, DNS, AI | P6, P7, P9 | Planned (registry call is in-process, R-F) |
| R5-6 | §5.2 | Registry consumes `analysis.completed` (snapshot); publishes notifications | P9-01, P10 | Planned (no notification topic, D-22) |
| R5-7 | §5.3 | One-way sync calls | 02 §2 | Moot (one app) |
| R5-8 | §5.3 | Fan-out: 2 consumer groups | P6-07, P9-01 | Planned (D-20) |
| R5-9 | §5.3 | Events carry IDs, not emails | P5-05, 02 §5 | Planned |
| R5-10 | §5.3 | One MySQL, one schema per service | 02 §4 | Changed (D-16: one schema, tables owned by features) |
| R5-11 | §5.4 | Gateway JWT check + header stripping | 02 §6 | Removed (D-16, D-21): identity comes from the session, never from headers |
| R5-12 | §5.4.4 | Only the entry point is reachable | P1-02 | Planned (only Caddy) |
| R5-13 | §5.4.5 | Honest limit: header forging | — | Moot |
| R5-14 | §5.5 | Repository layout | P1-01 | Changed (M-01) |
| R5-15 | §5.5 | ~12–20 classes per service | CLAUDE.md | Context |

## §6 Flows

| ID | PRD § | Requirement | Destination | Status |
|---|---|---|---|---|
| R6-1 | §6.1 | Authorization Code + state, nonce, PKCE | P2-02 | Planned |
| R6-2 | §6.1 | Validate the token, `email_verified`, find/create by `sub` | P2-02 | Planned (`hd`: D-17) |
| R6-3 | §6.1 | Cookie HttpOnly, Secure, SameSite=Lax | P2-02 | Changed (session cookie, D-21) |
| R6-4 | §6.1 | 4 h lifetime; no refresh; `/me` re-issues | P2-02 | Changed (M-04; no re-issue needed) |
| R6-5 | §6.1 | HTTPS redirect except localhost | P14-03 | Planned |
| R6-6 | §6.2 | Upload → validate → Cloudinary → QUEUED (encrypted) → after commit event → 202 | P5 | Planned |
| R6-7 | §6.2 | Dedupe, decrypt, AI, checks, registry match, score, save, `analysis.completed` | P6, P7, P9 | Planned |
| R6-8 | §6.2 | Browser polls until READY | P12-02 | Changed (D-14: polls the submission; exhaustMap) |
| R6-9 | §6.3 | QUEUED → READY/PARTIAL/FAILED → DELETED | P5-05, P6-07, P11-01 | Planned (FAILED via D-28) |
| R6-10 | §6.3 | Review cases and scam reports have their own states | P10 | Planned |
| R6-11 | §6.4.1–2 | Ownership via snapshot; 1 per submission; ≤ 5/day | P10-01 | Planned |
| R6-12 | §6.4.3–4 | UNVERIFIED markers, distinct reporters, "Reported by N" | P10-01, P9-03 | Planned (D-08, D-33.5) |
| R6-13 | §6.4.5–7 | Admin queue; confirm → CONFIRMED + email; reject cleanup | P10-02 | Planned (D-33.4, D-13) |
| R6-14 | §6.5 | Ask my TPO; college match; If-Match; CONFIRMED_SCAM → CONFIRMED | P10-03 | Planned (D-04 evidence) |

## §7 Evidence pipeline, checks, scoring

| ID | Requirement | Destination | Status |
|---|---|---|---|
| R7-1 | 5 source kinds shown | P6-07, P12-02 | Planned |
| R7-2 | No scraping of Google, job sites or MCA | CLAUDE.md | Planned (guard) |
| R7-3 | AI never creates evidence; AI-extracted labelled | P7-02 | Planned |
| C1 | Money request → hard override | P6-02 | Changed (D-09 "no fee" rule) |
| C2 | Free-mail claiming a company +25 | P6-02 | Planned |
| C3 | Domain ≠ official +20 | P8-02 | Planned |
| C4 | Lookalike +30 | P8-02 | Changed (D-03 brand rule) |
| C5 | RDAP young domain +20/+10; none → UNAVAILABLE | P6-04 | Planned |
| C6 | No MX +15; no DMARC +5 | P6-05 | Planned |
| C7 | Email headers | — | Excluded (§4.1) |
| C8 | Off-platform contact +15 | P6-02 | Planned |
| C9 | Pressure language +10 each, max 2 | P6-02 | Planned |
| C10 | Confirmed domain/phone/UPI → override | P9-04 | Planned (R-B, D-08) |
| C11 | SimHash ≤ 3 confirmed → override unless official sender (+20) | P9-04 | Planned (D-18, R-A) |
| C12 | Unverified reporters: 3+ → +25, 1–2 → +10; never override | P9-04 | Planned |
| R7-4 | Weights are heuristics | P15-01 | Context |
| R7-5 | Bands and exact messages | P6-06, P12-02 | Planned |
| R7-6 | Two layers + manual learning loop | 02, README | Planned |
| R7-7 | Copied-letter guard | P9-04 | Planned |
| R7-8 | Unverified never overrides | P6-06 | Planned |

## §8 AI

| ID | Requirement | Destination | Status |
|---|---|---|---|
| R8-1 | Extraction to strict JSON | P7-02 | Planned |
| R8-2 | Explain on demand; never decide | P7-03 | Planned |
| R8-3 | AI never sees student names, emails or phones | P7-02 | Planned (D-07; residual risk documented) |
| R8-4 | Groq limits per organization | V-06 | Verified |
| R8-5 | 5-model chain | P7-01 | Changed (D-11: 3 Groq models) |
| R8-6 | Cerebras covers both tasks | — | Removed (D-11) |
| R8-7 | Comma-separated key lists | P7-01 | Planned (one key, one org) |
| R8-8 | One OpenAI-compatible client on RestClient | P7-01 | Planned |
| R8-9 | ~1,300/day capacity | 02 §11 | Changed (~230–300/day) |
| R8-10 | Fallback logic (skip exhausted, 10 s, 429, 5xx, invalid JSON, EMPTY) | P7-01 | Planned |
| R8-11 | 429 headers | P7-01 | Planned (duration format) |
| R8-12 | `llm_cache` by sha256 | P7-02 | Planned (30-day TTL, D-06) |
| R8-13 | Collapse and cap at ~6,000 chars | P7-02 | Planned |
| R8-14 | One call per analysis; explain on click | P7-02, P7-03 | Planned |
| R8-15 | Placeholder redaction | P7-02 | Planned (+ names, D-07) |
| R8-16 | Delimiters; fixed record; AI adds candidates only | P7-02 | Planned |
| R8-17 | Injection test: same band AI on/off | P7-02 | Planned |

## §9 Tech stack

| ID | Item | Destination | Status |
|---|---|---|---|
| R9-1 | Java 21 | D-01 | Planned |
| R9-2 | Spring Boot 3.x | D-01 | Changed (4.1.x) |
| R9-3 | Spring Cloud Gateway; JWT check in gateway | — | Removed (D-16, D-21) |
| R9-4 | Spring Security OAuth2 Client | P2-02 | Planned |
| R9-5 | Kafka KRaft + Spring Kafka | P1-02, P5-05, P6 | Planned (2 topics, D-20) |
| R9-6 | MySQL 8.4 + Flyway | P1-02, P1-03 | Planned (one schema; V-25 at P1) |
| R9-7 | Redis | — | Removed (D-12) |
| R9-8 | Caffeine | P6-04, P6-05 | Planned |
| R9-9 | Resilience4j | — | Removed (D-31) |
| R9-10 | PDFBox 3, Tika, dnsjava, Commons Text | P5-02, P6-05, P8-02 | Planned |
| R9-11 | Cloudinary SDK; Spring Mail; Groq via RestClient | P5-04, P3-02, P7-01 | Planned |
| R9-12 | Angular (standalone, signals, control flow, functional guards) | P4, P12 | Planned (Angular 22, D-01) |
| R9-13 | Caddy; Docker Compose; GitHub Actions + gitleaks | P1-02, P14, P1-01 | Planned |
| R9-14 | §9.1 Cloudinary free, PDF toggle, backend-only upload, private + signed URLs, deleted by retention | P5-04, P10-04, P11-01 | Planned (D-05) |
| R9-15 | §9.2 Gmail 500/day, app password, dedicated account | P3-02 | Planned |

## §10 Data model

| ID | Requirement | Destination | Status |
|---|---|---|---|
| R10-1 | `BINARY(16)` UUIDs, timestamps, InnoDB `utf8mb4` | All migrations | Planned |
| R10-2 | identity_db tables | P2-01, P3-02 | Changed (one schema; `college_domain`/`roster_entry` removed D-17; `tpo_assignment` added D-02) |
| R10-3 | submission_db tables | P5-01 | Planned (+ sweeper columns) |
| R10-4 | analysis_db tables | P6-01, P7-02, P8-01 | Planned (`known_company` global, D-24) |
| R10-5 | registry_db tables | P9-01, P10-01 | Planned (+ `scam_report.version`, `snapshot.signals_json`) |
| R10-6 | HMAC with a pepper | P9-02 | Planned |
| R10-7 | SimHash 5 steps; skip < 50 words; `BIT_COUNT`; filtered scan | P9-02, P9-03 | Planned |
| R10-8 | AES-256-GCM, 12-byte IV, key_version | P5-03 | Planned |
| R10-9 | Idempotent consumers via `processed_event` | P1-03, P6-01, P6-07, P9-01 | Planned (PK consumer + event) |

## §11 API

| ID | Endpoint | Destination | Status |
|---|---|---|---|
| API-1 | `/oauth2/authorization/google`, `/login/oauth2/code/google` | P2-02 | Planned |
| API-2 | `GET /api/auth/me` | P2-02 | Changed (no cookie re-issue, D-21) |
| API-3 | `POST /api/auth/logout` | P2-02 | Planned |
| API-4 | `GET /api/colleges?q=` | P3-01 | Planned |
| API-5 | `POST /api/memberships` | P3-02 | Planned |
| API-6 | `GET /api/memberships/pending`, `POST …/{id}/decision` | P3-02 | Planned |
| API-7 | `POST /api/colleges/{id}/roster` | — | Removed (D-17) |
| API-8 | `POST /api/admin/colleges`, `…/{id}/tpos` | P3-01 | Planned |
| API-9 | `POST /api/submissions` | P5-01 | Planned |
| API-10 | `GET /api/submissions`, `…/{id}` | P5-01 | Planned |
| API-11 | `GET /api/reports/{id}` (owner, or TPO for a case) | P6-07, P10-03 | Changed (D-04: TPO sees evidence via the review endpoint) |
| API-12 | `POST /api/reports/{id}/explain` | P7-03 | Planned |
| API-13 | `GET/POST/DELETE /api/known-companies` | P8-01 | Changed (ADMIN only, D-24) |
| API-14 | `POST /api/reviews` | P10-03 | Planned |
| API-15 | `GET /api/reviews/queue`, `POST …/{id}/decision` | P10-03 | Planned |
| API-16 | `POST /api/scam-reports` | P10-01 | Planned |
| API-17 | `GET /api/admin/scam-reports`, `POST …/{id}/decision` | P10-02 | Planned |
| API-18 | `GET /api/registry/domains/{domain}` | P10-05 | Planned |
| API-19 | `POST /internal/match` | — | Removed (in-process, R-F) |
| API-20 | RFC 9457 errors | P1-03 | Planned |
| API-21 | + `GET /api/admin/colleges` | P3-01 | Added (admin screen needs it) |
| API-22 | + `GET /api/reviews/{id}`, `…/{id}/pdf-link`, `GET /api/admin/scam-reports/{id}/pdf-link` | P10-03, P10-04 | Added (D-04, D-05) |
| API-23 | + `POST /api/admin/indicators/{id}/revert` | P10-02 | Added (D-13) |

## §12 Frontend

| ID | Requirement | Destination | Status |
|---|---|---|---|
| R12-1 | Standalone, signals, no NgRx, new control flow | P4-01 | Planned |
| R12-2 | Functional guards; one interceptor (401) | P4-01 | Planned |
| R12-3 | Lazy `/app`, `/tpo`, `/admin` | P4-01 | Planned |
| R12-4 | Polling every 2 s | P12-02 | Changed (D-14) |
| R12-5 | Built-in XSRF | P4-01, P2-03 | Planned |
| R12-6 | Plain text only; no `[innerHTML]` | P12-02 | Planned |
| SC-1..3 | Login · Choose path · Join college | P4-02 | Planned |
| SC-4..7 | Upload · Report · My submissions · Report scam | P12-01, P12-02 | Planned |
| SC-8 | TPO pending members (+ roster upload) | P12-03 | Changed (no roster upload, D-17) |
| SC-9 | TPO review queue | P12-03 | Planned (+ evidence and PDF link) |
| SC-10 | TPO known companies | — | Removed (D-24) |
| SC-11..13 | Admin: colleges and TPOs · unverified scam reports · global known companies | P12-04 | Planned (+ Revert, PDF link) |

## §13 System-design concepts

| ID | Concept | Destination | Status |
|---|---|---|---|
| R13-1 | API gateway | P2-03 (security), Caddy (routing) | Changed (D-16) |
| R13-2 | Async Kafka; one sync dependency behind a CB | P5-05, P6 | Changed (sync call is in-process; no CB, D-31) |
| R13-3 | AFTER_COMMIT publish + sweeper | P5-05, P6-07 | Planned (+ D-28 fixes) |
| R13-4 | HTTP Idempotency-Key; Kafka `processed_event` | P5-01, P6-01 | Planned |
| R13-5 | 3 retries → `.DLT`; timeouts AI 10 / RDAP 5 / DNS 3 s | P6-01, P6-04, P6-05, P7-01 | Planned (registry 2 s moot) |
| R13-6 | Rate limits (Redis token bucket) | P2-03, P5-01, P10-01 | Changed (D-12) |
| R13-7 | Caffeine caches; LLM cache permanent | P6-04, P6-05, P7-02 | Changed (LLM cache 30 days, D-06) |
| R13-8 | If-Match → 409 | P3-02, P10-02, P10-03 | Planned |
| R13-9 | Database per service | — | Changed (D-16) |
| R13-10 | Stateless services; JVM memory limits | M-09 | Changed (session in memory, D-21) |

## §14 Security (all controls kept, D-25)

| ID | Control | Proof | Destination | Status |
|---|---|---|---|---|
| S14-1 | `state`; PKCE; nonce | `LoginRedirectTest` | P2-02 | Planned |
| S14-2 | `email_verified` before joining or privileges | `OidcUserProvisioningServiceTest` | P2-02 | Planned (R-C) |
| S14-3 | `hd` claim | — | — | Removed with path A (D-17) |
| S14-4 | Users keyed on `sub` | provisioning test | P2-02 | Planned |
| S14-5 | Roles only from the DB; TPO pre-assigned; admin from env | provisioning test | P2-02, P3-01 | Planned |
| S14-6 | RS256 JWT, keys | — | — | Changed (D-21: session) |
| S14-7 | Cookie HttpOnly/Secure/Lax, 4 h | `SessionCookieTest`, S-01 | P2-02 | Planned |
| S14-8 | Cookie CSRF + Angular echo | `CsrfTest`, S-04 | P2-03 | Planned |
| S14-9 | Forward headers behind the proxy | redirect URI check | P2-02 | Planned |
| S14-10 | Scoped lookups; 404 not 403; IDOR proof (submission, report, scam report) | access tests, S-03 | P5-01, P6-07, P10-01, P10-04 | Planned |
| S14-11 | Trusted headers stripped | — | — | Moot (no header identity) |
| S14-12 | 5 MB, 10 pages, magic bytes, PDF bomb timeout, text only, server-side upload after validation | `FileValidatorTest`, S-06 | P5-02, P5-04 | Planned |
| S14-13 | SSRF: allow-listed RDAP, validated domain, never fetch letter URLs | `DomainValidatorTest`, S-05 | P6-03, P6-04 | Planned |
| S14-14 | AES-GCM; HMAC | `TextCipherTest`, `IndicatorHasherTest`, S-12 | P5-03, P9-02 | Planned |
| S14-15 | PII redaction before AI | `ProviderRequestRedactionTest` | P7-02 | Planned |
| S14-16 | 30-day deletion | `RetentionJobTest` | P11-01 | Planned (D-06) |
| S14-17 | No PII in logs | `LoggingAspectTest`, S-11 | P1-03 | Planned (D-27) |
| S14-18 | Audit log of TPO/admin decisions | decision tests | P1-03, P3, P10 | Planned (R-E) |
| S14-19 | DPDP note | — | P15-01 | Context |
| S14-20 | Security headers (CSP etc.) | `SecurityHeadersTest`, S-08 | P2-03 | Changed (D-10 CSP) |
| S14-21 | Only the entry point publishes ports | S-02 | P1-02, P14-01 | Planned |
| S14-22 | Secrets in `.env`; gitleaks | S-10 | P1-01 | Planned |
| S14-23 | Actuator health only | S-09 | P1-03 | Planned |
| S14-24 | Angular escaping; no `[innerHTML]` | grep check | P4, P12 | Planned |
| S14-25 | OWASP map (Dependabot, CI, pinned images, audit + request ID) | 05 §6 | P1-01, P1-03 | Planned |

## §15–§22

| ID | Requirement | Destination | Status |
|---|---|---|---|
| E-1 | Google, `APP_BASE_URL`, `ADMIN_EMAILS` | 06 §2, P2-02 | Planned |
| E-2 | JWT keys | — | Removed (D-21) |
| E-3 | AES key, HMAC pepper | P5-03, P9-02 | Planned |
| E-4 | MySQL, Kafka, Redis host | P1-02 | Changed (no Redis) |
| E-5 | `GROQ_API_KEYS`, `CEREBRAS_API_KEYS`, `AI_CHAIN` | P7-01 | Changed (no Cerebras) |
| E-6 | Cloudinary, SMTP | P5-04, P3-02 | Planned |
| E-7 | Config-only changes | P7-01 | Planned |
| R16-1 | OCI facts (2 OCPU / 12 GB, card, home region) | V-14 | Verified |
| R16-2 | Memory budget | M-09 | Ask |
| R16-3 | OCI steps 1–7 | P14, 06 §4 | Planned |
| R16-4 | Render backup | — | Ask (M-10) |
| R17-1 | One part at a time; never "the whole project" | 03, 07, CLAUDE.md | Planned |
| R17-2 | Run it + explain-it checkpoint per block | 03 §5, 07 §0 | Planned |
| R17-3 | Day plan with "done when" | 03 §1 | Changed (D-15) |
| R17-4 | Cut list; never-cut list | 03 §4, CLAUDE.md | Planned |
| T-1…T-7 | §18 tests: Scoring, DomainValidator, SubmissionAccess, LlmChain, PromptInjection, SimHash, CopiedLetterGuard | P6-06, P6-03, P5-01, P7-01, P7-02, P9-02, P9-04 | Planned |
| T-8 | Fake letters only | 05 §4, CLAUDE.md | Planned |
| R19 | 11 honest limitations in the README | P15-01 | Planned (adjusted) |
| R20 | Things to verify while building | 01 §6, 06 §5 | Planned |
| R21 | Resume bullets only after measuring | P15-01 | Context |
| R22 | Sources | 01 §6 | Context |

---

**Coverage check (2026-10-19):**
- Every PRD heading from §0 to §22 has at least one row.
- Every requirement is either **Planned**, **Changed** or **Removed** by an approved decision, **Excluded** per §4.1, or **Ask** (M-10, R16-2).
- Nothing was dropped without a decision.
