# P3 + P4 (Colleges, Membership, Email, Angular Shell) + Review Hardening — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:test-driven-development. Four tasks run in parallel worktrees (H, P3a, P3b, P4); the coordinator merges. Each task owns its files; touch a file outside your list only when this plan says so.

**Goal:** M3 — an admin creates a college and assigns a TPO; a student asks to join; the TPO approves with `If-Match` (stale → 409); the student is emailed after commit; in the browser: login → choose path → join college. Plus every deferred P1/P2 review item fixed.

**Spec:** `docs/plan/04-work-breakdown.md` P3-01, P3-02, P4-01, P4-02; `docs/plan/02` §7 (membership machine), §9 (frontend); PRD §3, §3.1 C, §4 F2/F9, §11, §12, §13.8; D-02, D-10, D-14, D-17, D-22, D-26, D-33.3, D-33.7; R-C, R-D, R-E. Decisions taken: **M-05 = nginx container** (`nginxinc/nginx-unprivileged`, matches PRD/02 §1 and CLAUDE.md stack).

## Global Constraints

- Identity only from the session (`@AuthenticationPrincipal AppOidcUser` / `CurrentUser`). Roles via `@PreAuthorize`. Data scoping from the database (a TPO's college = `UserDirectory.findTpoCollegeId`), never from the request.
- Other users' / other colleges' data → **404**; wrong role → 403 (R-D). Unverified email can't join a college (R-C) → 403 `EMAIL_NOT_VERIFIED`.
- Every TPO/admin decision writes `audit_log` via `AuditLogService.record(actorId, action, targetId)` (R-E).
- Decisions use `If-Match: <version>` (also accept the quoted ETag form `"3"`); missing → **428** `PRECONDITION_REQUIRED`; stale → **409** `CONFLICT`. Responses carry `ETag: "<version>"`.
- Errors: RFC 9457 via `ApiException` subclasses (`common/web`). Only task H edits `ProblemDetailExceptionHandler`.
- Emails (D-22, D-33.7): sent **after commit** (`@TransactionalEventListener(AFTER_COMMIT)`), SMTP timeouts 10 s, failure logged as class only — the decision stands. If `spring.mail.username` is blank, log "email skipped: SMTP not configured" (no address) and return.
- Logging (D-27): never emails, names, roll numbers, reasons.
- Flyway: next free version is **V6** (`V6__create_membership.sql`, task P3b). One DDL change per file.
- Integration tests extend `support/IntegrationTest`; POST/PUT/DELETE use `support/RealCsrf.xsrf(mockMvc)` — **never** spring-security-test `csrf()` (it poisons the shared context). Principals via `support/TestPrincipals.signedIn(userId, role)`.
- Test names `method_condition_expected`; fake data only.

## API contract (P3 implements, P4 consumes)

| Method | Path | Who | Request | Response |
|---|---|---|---|---|
| GET | `/api/auth/me` | signed in | — | `{userId, name, email, role, college: {id, name} \| null, membershipStatus: "NONE"\|"PENDING"\|"APPROVED"\|"REJECTED"}`; 401 problem if not signed in. For a USER, `college` = the membership's college (any status); for a TPO, their college. |
| POST | `/api/auth/logout` | signed in | XSRF header | 204 |
| GET | `/api/colleges?q=` | signed in | `q` optional | `[{id, name, city}]`, ACTIVE only, name contains `q` (case-insensitive), ordered by name, max 20 |
| POST | `/api/admin/colleges` | ADMIN | `{name, city}` (name 2–200, city ≤ 100) | 201 `{id, name, city, status, version}`; duplicate name → 409 |
| GET | `/api/admin/colleges` | ADMIN | — | `[{id, name, city, status, tpoEmails: [..], pendingTpoEmails: [..]}]` ordered by name |
| POST | `/api/admin/colleges/{id}/tpos` | ADMIN | `{email}` | 200 `{email, status: "ASSIGNED"\|"PENDING"}`; unknown college → 404 |
| POST | `/api/memberships` | USER | `{collegeId, rollNo (1–50), department (1–100)}` | 201 `MembershipResponse`; existing PENDING/APPROVED → 409 `MEMBERSHIP_EXISTS`; unverified email → 403 `EMAIL_NOT_VERIFIED`; unknown/inactive college → 404 |
| GET | `/api/memberships/me` | signed in | — | 200 `MembershipResponse` or 404 |
| GET | `/api/memberships/pending` | TPO | — | `[{id, studentName, studentEmail, rollNo, department, requestedAt, version}]` own college only, oldest first |
| POST | `/api/memberships/{id}/decision` | TPO | `If-Match`; `{decision: "APPROVE"\|"REJECT", reason}` (reason ≤ 500, required for REJECT) | 200 `MembershipResponse` + `ETag`; stale 409; missing If-Match 428; other college 404; not PENDING 409; reject without reason 400 |

`MembershipResponse = {id, collegeId, collegeName, rollNo, department, status, reason, version}`.
Login is a full-page navigation to `/oauth2/authorization/google`; failed/cancelled sign-in lands on `/app?login=failed`.

---

### Task H (agent): deferred review fixes (backend only)

Owns: `auth/OidcUserProvisioningService` (+test), `auth/TpoAssignmentRepository` (only a new `deleteByEmailReturningCount`-style method), `common/persistence/BaseEntity`, `auth/UserRateLimitFilter` (+test), `auth/SecurityConfig`, `common/web/ProblemDetailExceptionHandler` (+test), `infra/mysql/init/01-app-user.sh`, `.github/workflows/ci.yml` (permissions only), `.github/dependabot.yml`, existing ITs (`UserProvisioningIT`, `EntityMappingIT`, `SqlErrorLoggingIT`, `FlywayMigrationIT`).
1. First-login race: catch `DataIntegrityViolationException` on insert, re-read by `sub`.
2. TPO-assignment race: consume via a `@Modifying` delete returning the row count; only the winner (count 1) gets TPO + audit.
3. Exact email match: after the lookup, require `assignment.getEmail().equals(normalisedEmail)` (collation is accent-insensitive).
4. `BaseEntity`: flip "new" on `@PrePersist` + `@PostLoad` (test: create + delete in one transaction removes the row).
5. 429 body via `ProblemResponseWriter` (constructor injection; `SecurityConfig` passes it).
6. `oauth2Login` with `HttpSessionOAuth2AuthorizedClientRepository` (no Google tokens kept after logout).
7. Timeouts for Google calls (token endpoint, userinfo, JWKS): 5 s connect / 10 s read.
8. Framework ProblemDetails (404/405/400/415…) carry `errorCode` and `requestId`; `OptimisticLockingFailureException` → 409 `CONFLICT`; `HandlerMethodValidationException`/`MethodArgumentNotValidException` → 400 `VALIDATION_FAILED` with field names only (no rejected values); 500 log adds cause-chain class names and the first `com.offershield` frame (still no messages).
9. Tests: root-logger appender in the ProblemDetail 500 test; `$.requestId` equals the `X-Request-Id` header; `RequestIdFilter` clears MDC when the chain throws; `LoggingAspect` controller + `@KafkaListener` branches.
10. MySQL init: escape backslashes as well as quotes; replace `MYSQL_PWD` with a `--defaults-extra-file` temp file (0600, deleted after use).
11. CI: `permissions: contents: read, pull-requests: read`. Dependabot: ignore `version-update:semver-major` for docker/docker-compose images.
12. Move `UserProvisioningIT`/`EntityMappingIT`/`SqlErrorLoggingIT` onto the shared container (keep them `*IT`).

### Task P3a (agent): colleges, admin, TPO assignment (P3-01)

Owns: `college/*` (except `CollegeService.findActiveSummary`/`findSummary`, which exist — extend, don't break), new `auth/TpoAssignmentService` (+ new methods appended to `AppUserRepository`), tests `CollegeAdminControllerTest` (`nonAdmin_403`, `assignExistingUser_promoted`, `assignNewEmail_pending`, `audited`), `CollegeSearchControllerTest` (max 20, ACTIVE only, case-insensitive), admin list test.
- Assign rules: normalise email (trim, lower-case ROOT). A **verified** user with that email exists → set `tpoCollegeId`, role TPO (ADMIN stays ADMIN), audit `TPO_ASSIGNED`. Otherwise upsert `tpo_assignment` (re-assign to the new college if one exists), audit `TPO_ASSIGNMENT_CREATED`. Unverified users are never promoted (R-C).

### Task P3b (agent): membership + email (P3-02)

Owns: `V6__create_membership.sql`, `membership/*`, `notification/*`, `auth/MeService` (+ its test), `common/web/PreconditionRequiredException` (428) and `common/web/ForbiddenException` (403) if needed, tests `MembershipDecisionTest` (`approve_ok_emailSent_audited`, `staleVersion_409`, `otherCollege_404`, `rejectWithoutReason_400`, `rejectedCanReapply`), `MembershipRequestTest` (409 existing, 403 unverified, 404 college), `EmailServiceTest.smtpFailure_loggedDecisionKept`, a GreenMail IT proving a real SMTP send after commit (and none on rollback).
- Table `membership(id, user_id UNIQUE → app_user, college_id → college, roll_no, department, status CHECK PENDING/APPROVED/REJECTED, reason NULL, decided_by NULL → app_user, decided_at NULL, version, created_at, updated_at)`; index `(college_id, status, created_at)`.
- One row per student: re-applying after REJECTED moves the same row back to PENDING (D-33.3), possibly another college; clears reason/decision fields.
- Audit actions `MEMBERSHIP_APPROVED` / `MEMBERSHIP_REJECTED` (actor = TPO, target = membership id).
- `MeService`: USER → college + status from `MembershipService`; TPO → their college; status `NONE` when no row.

### Task P4 (agent): Angular shell (P4-01, P4-02)

Owns: `frontend/**`, `Caddyfile` (frontend route + security headers on frontend responses), `docker-compose.yml` (**only** a new `frontend` service on the `edge` network; Caddy `depends_on` it), `.github/workflows/ci.yml` (**only** a new `frontend` job), `.github/dependabot.yml` untouched (H owns it).
- Angular 22 via `angular-new-app`; standalone, signals, zoneless, `@if/@for`, Vitest; `inlineCritical: false`; system font stack (no external fonts); relative `/api` URLs; one interceptor (401 → `/login`); no `[innerHTML]`.
- Routes: `/login`, `/start` (choose path), `/join`, lazy `/app`, `/tpo`, `/admin` with `canMatch` (`authGuard`, `roleGuard`). `/app?login=failed` → shown on the login page ("Sign-in didn't complete. Try again.").
- Tests: `auth.guard.spec`, `role.guard.spec`, `unauthorized.interceptor.spec`, `join-college.spec` (`submit_showsPending`, `409_showsExisting`).
- Serve: multi-stage `frontend/Dockerfile` (node 24 build → `nginxinc/nginx-unprivileged` on 8080, SPA fallback, long cache for hashed assets, no-cache for `index.html`).
