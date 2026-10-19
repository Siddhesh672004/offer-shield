# 01 — Decisions, Open Items, Verification and Risks

**Status:** every planning decision was **approved by the user on 2026-10-19**, over 9 question rounds. The PRD (`OfferShield_PRD.md`) is unchanged. Where a decision below differs from the PRD, **the decision wins** (see CLAUDE.md, precedence).
**Guiding rule from the user:** free services only (no paid plans or card-only trials), basic-to-medium scope, and nothing so complex that a person can't understand it while building. Version upgrades are pre-approved; every simplification needs the user's approval.

---

## 1. Decision log (all Approved, 2026-10-19)

| ID | Topic | Decision | PRD impact |
|---|---|---|---|
| D-01 | Versions | Java 21 · Spring Boot **4.1.x** · Angular **22.2.x** · Node 24 LTS (Boot 3.x has had no free fixes since June 2026) | §9 "3.x / GridPulse versions" → upgraded |
| D-02 | TPO setup | The admin pre-assigns a TPO by Google email and college. The first sign-in with a **verified** email makes that person a TPO (`tpo_assignment` table) | §3 as written |
| D-03 | Lookalike rule (C4) | **Simple brand rule:** map look-alike characters (0→o, 1→l, rn→m, …), then flag when the domain *contains* a known company's name or is ≤ 2 edits from it, and isn't an official domain | §7.1 C4 changed (the PRD's rule missed its own example) |
| D-04 | TPO/admin evidence | Evidence comes from the registry **report snapshot**, filled by the `analysis.completed` consumer and including a signal summary. `GET /api/reports/{id}` stays owner-only | §11 "TPO for a review case" served via the review case + snapshot |
| D-05 | Original PDF | Stored as a **private Cloudinary** file. A **short-lived download link** is available only to the TPO of a review case in their college, and to the admin for a submission with a scam report. Deleted at day 30 | §9.1 as written; adds the missing endpoint |
| D-06 | Retention | **Full 30-day cleanup** by one daily job: submission text, Cloudinary PDF, report + signals, snapshot (unless an open case or report still needs it), old AI-cache rows. Hashed indicators are kept. Kafka keeps messages 3 days | Extends §14.6 |
| D-07 | AI privacy | Mask phones, emails and UPI IDs, **plus names after greetings/labels** ("Dear", "Mr./Ms.", "Candidate Name:"), before sending to Groq. Enable Groq Zero Data Retention. Residual risk documented | Implements §8.1 |
| D-08 | Shared-domain guard | Free-mail domains (from a fixed public list) and known companies' official domains **never become scam markers**. Applied the same way to everyone. (The user delegated this: "best, no bias") | Fixes a §7 false-accusation hole |
| D-09 | "No fee" sentences | C1 ignores a sentence with a negation (no/not/never/free of/without …) **and** no amount (₹, Rs, a number). "No interview, pay ₹5,000 fee" still triggers | §7.1 C1 refined |
| D-10 | CSP | `default-src 'self'` stays, plus **`style-src 'self' 'unsafe-inline'`** (Angular's documented fallback). `inlineCritical: false`; self-hosted fonts | §14.7 adjusted |
| D-11 | AI provider and scope | **Groq only** (free, no card), across its models; **extraction + "Explain simply"**. No Cerebras (it's a $5, card-required trial). Expect ~230–300 AI analyses/day, then rule-only "AI unavailable" | §8.2 chain shortened |
| D-12 | Rate limits | **DB counts:** 5 uploads per 10 min and 5 scam reports per day, per user. Plus a **Bucket4j in-memory limit of 60 requests/min per signed-in user**. No per-IP limit and no Redis | §13.6 changed |
| D-13 | Undo a confirmation | The admin gets a **Revert** button: CONFIRMED → back to unconfirmed, written to the audit log | Implements §7 "reversible" |
| D-14 | Slow-network polling | Poll status every 2 s and **wait for the previous check** (exhaustMap). Stop after 3 min with "Still processing — check My submissions" | §12 operator changed |
| D-15 | Schedule | **Flexible, about 1 week**, phase by phase with checkpoints. Not a hard 72 h | §17 timing replaced |
| D-16 | Architecture | **One Spring Boot app** with feature packages, **plus Kafka**. **No API gateway, no Redis, no separate services** | §5 changed (biggest simplification) |
| D-17 | College joining | Colleges + TPO role + **manual join approval only**. Domain auto-join (path A) and roster CSV (path B) are **removed** | §3.1 paths A and B cut |
| D-18 | SimHash | **Kept**, with the copied-letter guard (C11) and its tests | §7.1 C11, §10.1 as written |
| D-19 | Online domain checks | **RDAP (domain age) + DNS (MX, DMARC) kept**, with the SSRF guard | §7.1 C5/C6 as written |
| D-20 | Kafka topics | **Two:** `submission.created` (→ analysis) and `analysis.completed`, with **two consumer groups:** status update and registry snapshot (fan-out) | §5.2 minus `notification.requested` |
| D-21 | Login session | Google sign-in + **Spring Security's standard session cookie** (HttpOnly, Secure, SameSite=Lax) + Angular-compatible CSRF. **No own JWT or key pair** | §6.1/§14.2 changed |
| D-22 | Emails | Gmail SMTP from a dedicated free account, sent **directly** right after the decision is saved. A failure is logged; the decision stands | §5.3 `notification.requested` removed |
| D-23 | Hosting | **Oracle Cloud Always Free VM**, Docker Compose, Caddy (HTTPS), DuckDNS. The card at signup is for identity only | §16 as written |
| D-24 | Known companies | **Admin-only global list.** About 10 big employers are seeded, and the **user verifies each official domain** first | §4 F8 per-college lists removed |
| D-25 | Security baseline | **Every PRD §14 control kept** (see CLAUDE.md invariants) | — |
| D-26 | UI | **Clean custom CSS** with a few shared components; no UI library | — |
| D-27 | Extras | **CI + gitleaks**, **Dependabot**, and **AOP logging**: an `@Around` aspect logs class.method, user ID, submission ID, duration and errors, **never arguments or return values**, plus a request ID on each line (also carried in Kafka headers), to `logs/offershield.log`, rolled daily and kept 14 days. *Not chosen:* nightly DB backup | §14.8 A09 met via request ID |
| D-28 | Kafka safety | The PRD approach **plus fixes:**<br>• publish after commit<br>• sweeper backoff 2/4/8/16 min → FAILED<br>• `processed_event` dedupe<br>• 3 retries → `<topic>.DLT`<br>• re-send `analysis.completed` when a duplicate arrives after a crash | §13.3 hardened |
| D-29 | Tests | The PRD's 7 tests + security tests (IDOR, SSRF, CSRF, upload) + one test per risky rule. Real MySQL via Testcontainers for DB tests | §18 extended |
| D-30 | Groq availability | The user creates the Groq account before the AI phase. If it doesn't work from India, reports run rule-only | Closes V-24 at P7 |
| D-31 | Resilience | **Timeouts + Caffeine cache only:** RDAP 5 s, DNS 3 s, Groq 10 s → `UNAVAILABLE`/PARTIAL. **No Resilience4j** | §9 Resilience4j removed |
| D-32 | Database | **MySQL 8.4.** Prove a Flyway migration on day 1 (P1); if it fails, stop and decide again | §9 as written |
| D-33 | Small rules | 1. Pasted text ≤ 20,000 chars; an image-only PDF → "paste the text instead" (no OCR).<br>2. `Idempotency-Key`: a repeat returns the same submission.<br>3. A rejected student may re-apply.<br>4. An admin reject removes that reporter from its markers; markers with no reporters stop counting.<br>5. "Reported by N" = distinct people across matching markers.<br>6. The sender domain = the typed sender email, else the first email in the letter.<br>7. Emails: student ← TPO join/review decisions; reporter ← admin confirm/reject | Fills PRD gaps |

## 2. What changed against the PRD (summary)

| Removed / simplified | PRD § | Decision | Approx. saving |
|---|---|---|---|
| 4 microservices → 1 app; API gateway; trusted `X-User-*` headers; 4 schemas → 1 | §5, §13.1, §13.9 | D-16 | ~10 h + much less to learn |
| Redis rate limiter; per-IP limit | §13.6 | D-12 | ~1 h |
| Own RS256 JWT, key pair, `/me` cookie re-issue | §6.1, §14.2 | D-21 | ~2 h |
| `notification.requested` topic | §5.2 | D-20, D-22 | ~1.5 h |
| Domain auto-join (path A), roster CSV + fuzzy match (path B) | §3.1 | D-17 | ~3 h |
| Cerebras in the AI chain | §8.2 | D-11 | ~0.5 h + no card |
| Resilience4j circuit breaker | §9, §13.5 | D-31 | ~1 h |
| Per-college known-company lists | §4 F8, §11 | D-24 | ~1 h |
| **Added by decision:** PDF download links (D-05), Revert button (D-13), AOP file logging (D-27), Bucket4j (D-12), shared-domain guard (D-08), no-fee rule (D-09), name masking (D-07), sweeper cap + re-send fix (D-28) | — | — | ~+6 h |

## 3. Minor technical items to confirm at the start of the phase that needs them

These are low-stakes defaults. The phase prompt asks you about each one before any code is written; nothing is decided silently.

| ID | Item | Recommended default | Ask at |
|---|---|---|---|
| M-01 | Repository layout | `backend/` (one Maven module) + `frontend/` + root `docker-compose.yml`, `Caddyfile` | P1 |
| M-02 | Package naming | `com.offershield.<feature>` (`auth`, `college`, `membership`, `submission`, `analysis`, `ai`, `company`, `registry`, `review`, `scamreport`, `notification`, `retention`, `common`) | P1 |
| M-03 | Local entry point | Caddy also runs locally at `http://localhost`, with the same routing as prod (`/api`, `/oauth2`, `/login` → app; everything else → frontend) | P1 |
| M-04 | Session lifetime | 4 h (the PRD's session length) | P2 |
| M-05 | How Angular is served | nginx container behind Caddy (as in the PRD), or Caddy `file_server` (one container fewer) | P4 |
| M-06 | Registrable domain (e.g. `x.co.in`) for RDAP/C4 | Guava `InternetDomainName.topPrivateDomain()` (one method), or a small hand-made suffix list | P6 |
| M-07 | PDF download-link lifetime | 5 minutes | P10 |
| M-08 | UI language | English only (C1/C9 keyword lists include Hinglish) | P12 |
| M-09 | Container memory limits | App 1 GB (heap 60 %), MySQL 1.5 GB, Kafka 1 GB, Caddy + nginx 128 MB (≈ 3.7 GB of 12 GB) | P14 |
| M-10 | Render backup runbook (PRD §16.4, demo-day backup) | Not needed for v1: the fallback is "any ~4 GB Linux VM with the same compose file". Alternative: write a short Render + Aiven runbook (docs only) | P14 |

**Answers (2026-10-19, P1):** M-01, M-02, M-03 → the recommended defaults. Extra item: Maven isn't installed on the dev PC, so builds use the **Maven Wrapper** (`backend/mvnw`, Maven 3.9.16, SHA-256 pinned); every `mvn` command in the plan is now `backend/mvnw`. From P1 on, the user asked for no further permission questions: remaining M-items take their recommended default and are recorded here.

**Answers (2026-10-20, P2):** M-04 → 4 h session (recommended default; `server.servlet.session.timeout: 4h`). Google client ID/secret and `ADMIN_EMAILS` are deferred by the user until after all phases; until then the app starts with `GOOGLE_CLIENT_ID=not-configured` and the live sign-in checks are recorded as not executed.

**Answers (2026-10-20, P3/P4):** M-05 → nginx container (`nginxinc/nginx-unprivileged`, non-root, port 8080) behind Caddy, matching PRD/02 §1. SMTP credentials are deferred with the other secrets; until then `EmailService` logs "email skipped: SMTP not configured" and decisions still commit.

## 4. Rules that follow directly from the PRD (no choice involved; listed for transparency)

| ID | Rule | PRD source |
|---|---|---|
| R-A | AI fields may only **add** risk. The C11 copied-letter downgrade (High → +20) needs the company to be identified by rules (regex/known-company name in the text), never from AI alone | §2.1, §8.6 ("AI never lowers a score") |
| R-B | C10/C11 overrides use only **CONFIRMED** markers. Unverified markers feed only C12 and never override | §7.3 |
| R-C | `email_verified != true` can still sign in as USER, but never matches `ADMIN_EMAILS` or a TPO assignment, and can't join a college | §3, §14.1 |
| R-D | Other users' data → **404**, never 403 (scoped queries). Role failures → 403 | §14.3 |
| R-E | Every TPO/admin decision (membership, review, scam report, revert, TPO assignment) writes `audit_log` | §14.6 |
| R-F | `/internal/match` no longer exists: matching is an in-process call inside the app | §11 (consequence of D-16) |

## 5. PRD inconsistencies found in the audit — status after the decisions

| ID | Finding | Status |
|---|---|---|
| I-01 | §17 lists 4 tests; §18 lists 7 | Resolved: §18 + D-29 |
| I-02 | The §17 hours don't add up (37 h of blocks vs ~32; Day 3 = 15 h) | Resolved: D-15 |
| I-03 | The C4 rule can't flag `inf0sys-careers.com` | Resolved: D-03 |
| I-04 | Header strip set incomplete | **Moot:** no gateway headers (D-16, D-21) |
| I-05 | SimHash placed in registry, but text lives in analysis | Resolved: computed in `analysis`, stored and compared in `registry` (one app) |
| I-06 | No producer for snapshot `phone_hmacs` | Resolved: in-process HMAC during matching; values travel in `analysis.completed` (D-20) |
| I-07 | `scam_report` has no `version` for If-Match | Resolved: add `version` |
| I-08 | Signed URLs promised, but no endpoint | Resolved: D-05 |
| I-09 | "Reversible" with no API | Resolved: D-13 |
| I-10 | Name redaction vs §8.5 | Resolved: D-07 |
| I-11 | LLM cache "permanent" vs 30-day deletion | Resolved: D-06 |
| I-12 | Polling the report can't see FAILED | Resolved: poll `GET /api/submissions/{id}` (D-14) |
| I-13 | No build block for the retention job | Resolved: phase P11 |
| I-14 | Ports: gateway vs Caddy | Resolved: only Caddy publishes ports |
| I-15 | TPO pre-assign has no storage | Resolved: D-02 |
| I-16 | TPO/admin evidence | Resolved: D-04 |
| I-17 | Indicator "removed" vs the REJECTED status | Resolved: D-33 rule 4 |
| I-18 | Nothing sets FAILED | Resolved: D-28 |
| I-19 | analysis dual write | Resolved: D-28 re-send fix |
| I-20 | C11 "that company" ambiguity | Resolved: claimed company per the known list + R-A; limitation documented |
| I-21 | Cerebras isn't free | Resolved: D-11 |
| I-22 | Emails for automatic approvals | **Moot:** manual joins only (D-17) |
| I-25 | Per-IP limit vs campus NAT | Resolved: D-12 |
| I-26 | CSP breaks Angular | Resolved: D-10 |
| I-27 | Groq reset headers are durations | Handled in P7 |
| I-28 | Groq Qwen is Preview | Handled: order `gpt-oss-120b → gpt-oss-20b → qwen3.8-27b`; an unknown model is skipped |
| I-29 | NIXI RDAP may omit the registration date | Handled: `UNAVAILABLE` |
| I-31 | Boot 3.x unsupported | Resolved: D-01 |
| I-32 | 512 MB containers vs heap | Resolved: M-09 |
| I-33 | `gmail.com` as a scam marker | Resolved: D-08 |
| I-34 | "No fee" false positives | Resolved: D-09 |
| I-35 | Audit log of membership decisions | Resolved: one shared `audit_log` (R-E) |
| I-36 | AI could lower a score through the C11 guard | Resolved: R-A |

(I-23, I-24 and I-30 belonged to the microservice/Render design and no longer apply.)

## 6. External verification register (checked 2026-10-19)

| ID | Fact | Result | Status | Source |
|---|---|---|---|---|
| V-01 | Spring Boot support | 3.5 OSS ended 2026-06-30; **4.1.1** supported to 2027-07-31 | Verified | https://api.spring.io/projects/spring-boot/generations |
| V-03 | CSRF for an SPA; PKCE; nonce | Servlet `csrf(c -> c.spa())` exists since Security 7.0. PKCE is on by default in 7.x. Nonce is sent with `openid` | Verified | https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html |
| V-05 | DLT topic name | Spring Kafka ≥ 3.3 defaults to `<topic>-dlt`, so we set `<topic>.DLT` explicitly (PRD name) | Verified | https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html |
| V-06 | Groq limits/headers | Org-level limits; `retry-after` in seconds; reset headers are durations (`-requests` = per day, `-tokens` = per minute) | Verified | https://console.groq.com/docs/rate-limits |
| V-07 | Groq models | `openai/gpt-oss-120b`, `openai/gpt-oss-20b` (production), `qwen/qwen3.8-27b` (Preview): 30 RPM / 1K RPD / 8K TPM / 200K TPD each | Verified | https://console.groq.com/docs/models |
| V-09 | JSON output and reasoning | `json_object` supported; `reasoning_effort: low`, `include_reasoning: false`. Whether reasoning tokens count toward limits is **unverified**, so measure it at P7 | Partly | https://console.groq.com/docs/reasoning |
| V-10 | Cloudinary private files + download link | `private` type; `privateDownload(publicId, format, {expires_at})`, which bills 2× bandwidth. Whether the free plan's "Allow delivery of PDF" setting also affects private download links is **unverified**, so test it at P5 | Partly | https://cloudinary.com/documentation/control_access_to_media |
| V-11 | Cloudinary free plan | 25 credits/month; PDF delivery blocked by default on Free | Verified | https://cloudinary.com/pricing |
| V-12 | Google OIDC | Check the `hd`/`email_verified` claims server-side; HTTPS redirect except localhost; "Testing" caps at 100 users | Verified | https://developers.google.com/identity/openid-connect/openid-connect |
| V-13 | Gmail SMTP | 500 emails or recipients/day; app password needs 2-Step Verification | Verified | https://support.google.com/mail/answer/22839 |
| V-14 | OCI Always Free | A1: 2 OCPU / 12 GB; home region only; card at signup. The host firewall allows only SSH; don't use UFW | Verified | https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm |
| V-15 | arm64 images | Temurin 21, `mysql:8.4`, `apache/kafka:4.3.1`, nginx and caddy are all arm64. Avoid Bitnami (frozen) | Verified | https://hub.docker.com/_/mysql |
| V-16 | Docker ports vs firewall | Published ports bypass ufw; publish only Caddy | Verified | https://docs.docker.com/engine/network/packet-filtering-firewalls/ |
| V-18 | DuckDNS + Caddy | Live; HTTP-01 needs port 80 | Verified | https://caddyserver.com/docs/automatic-https |
| V-19 | IANA RDAP bootstrap | `.in` → NIXI, which sometimes omits the registration event | Verified | https://data.iana.org/rdap/dns.json |
| V-20 | Competitor search | Partially supported (§7) | Done | — |
| V-21 | Seed company domains | The **user verifies** them before P8 | Pending (user) | — |
| V-22 | Angular | 22.2.3; LTS to 2028-06; Node `^22.22.3 ‖ ^24.15 ‖ ≥26` | Verified | https://angular.dev/reference/releases |
| V-23 | Groq terms | No training on API data; up to 30-day logs unless Zero Data Retention; multiple accounts banned | Verified | https://console.groq.com/docs/your-data |
| V-24 | Groq usable from India | **Unverified.** The user checks when signing up (D-30) | Pending (user) | — |
| V-25 | Flyway + MySQL 8.4 | Not on Redgate's list. **Proved at P1 (2026-10-19):** Flyway 12.4.0 "Successfully applied 2 migrations" on `mysql:8.4` (Testcontainers IT and the compose stack) | Verified | https://documentation.red-gate.com/fd/supported-databases-and-versions-143754067.html |
| V-26 | Library versions | PDFBox 3.0.8; tika-core 3.3.3; dnsjava 3.6.5; commons-text 1.15.0; `cloudinary-http5` 2.5.0; Bucket4j (pin at P2); Testcontainers 2.x | Verified (Bucket4j at P2) | https://repo1.maven.org/maven2/ |

V-02, V-04, V-08 and V-17 (gateway, Redis, Cerebras, Render/Aiven) no longer apply after D-16, D-12, D-11 and D-23.

## 7. Competitor search (PRD §1.2; 2026-10-19)

**Partially supported.** No tool found takes an offer letter plus sender, phone and UPI and returns deterministic, evidence-backed signals for Indian students. Pieces exist separately:
- I4C "Report & Check Suspect" (cybercrime.gov.in): national phone/UPI lookup
- Fake Job Detector India: an ML score for job-post text
- Norton Genie / Bitdefender Scamio: generic AI verdicts
- ScamAdviser: domain trust
- Employer checks: Tech Mahindra QR codes, Reliance reference lookup

What still sets OfferShield apart: PDF letter input, explainable rules, one combined report, a letter-fingerprint registry, and TPO review.

## 8. Risks (updated)

| ID | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| K-01 | Scope still about 1 week of work | Med | Med | Phases with checkpoints (D-15); PRD cut order in 03 §5 |
| K-02 | Google login + CSRF + Angular cookies (first-time setup) | Med | High | Servlet `csrf.spa()` (V-03); test it at P2 before moving on |
| K-03 | Kafka reliability bugs (stuck or duplicate submissions) | Med | Med | D-28 fixes + tests |
| K-04 | False High on a genuine letter | Med | High | D-08, D-09, R-A, R-B + tests |
| K-05 | Groq unavailable or over quota | Med | Low | Rule-only fallback (never cut) |
| K-06 | Cloudinary private download needs the PDF setting | Med | Low | Test on day 1 of P5 (V-10) |
| K-07 | OCI capacity or firewall trouble | Med | Med | Runbook in 06; any ~4 GB Linux VM works as a fallback |
| K-08 | No backups (not chosen) | Low | Med | Documented limitation; can be added later |
| K-09 | Flyway on MySQL 8.4 | Low | Med | Proved at P1 (D-32) |

## 9. Deferred (post-v1)

| Origin | Items |
|---|---|
| PRD §4.1 | Outbox; SSE; email-header analysis (C7); SimHash LSH; full Testcontainers suite; OTP sign-up; vector DB; mobile, WhatsApp, ML |
| Removed by decision (can return later) | Domain auto-join and roster CSV (D-17); per-college company lists (D-24); `notification.requested` email queue (D-22); Cerebras (D-11); Resilience4j (D-31); per-IP limits (D-12) |
| Not chosen | Nightly DB backup; strict nonce-based CSP |
