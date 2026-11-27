# 06 — Deployment and Verification

**Scope:** secrets, the local Docker workflow, provider checks, the Oracle Cloud runbook (D-23) and operational checks.
**Nothing here was executed during planning.** No credentials were created and nothing was deployed.

## 1. Environments

| Env | URL | Public ports | Purpose |
|---|---|---|---|
| Local | `http://localhost` | Caddy `127.0.0.1:80` only | Daily build and test (same routing as prod) |
| OCI production | `https://<name>.duckdns.org` | Caddy 80/443 only | Real use (D-23) |

## 2. Secrets and configuration (all free; values only in the git-ignored `.env`)

| Variable | How to get it |
|---|---|
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | Google Cloud Console → OAuth client (Web). Redirect URIs: `http://localhost/login/oauth2/code/google`, and later `https://<name>.duckdns.org/login/oauth2/code/google` |
| `APP_BASE_URL` | `http://localhost` locally, `https://<name>.duckdns.org` in prod |
| `APP_DOMAIN` | `<name>.duckdns.org` (Caddy, prod only) |
| `ADMIN_EMAILS` | Your Google email(s) |
| `TEXT_AES_KEY_BASE64` | `openssl rand -base64 32` (must decode to 32 bytes) |
| `INDICATOR_HMAC_PEPPER` | `openssl rand -base64 48` |
| `MYSQL_ROOT_PASSWORD`, `DB_PASSWORD` | `openssl rand -base64 24` each (`DB_PASSWORD` is for the user `offershield_app`) |
| `KAFKA_BOOTSTRAP` | `kafka:9092` |
| `GROQ_API_KEYS`, `AI_CHAIN` | console.groq.com (free, no card; **one org only**, because Groq bans multiple accounts). Turn on Zero Data Retention. `AI_CHAIN=groq:openai/gpt-oss-120b,groq:openai/gpt-oss-20b,groq:qwen/qwen3.8-27b` (D-11) |
| `CLOUDINARY_URL` | Cloudinary free account (no card) |
| `SMTP_USERNAME`, `SMTP_APP_PASSWORD` | A **dedicated** Gmail account → 2-Step Verification → App password (D-22) |

Not needed any more (decisions): JWT keys (D-21), Redis (D-12), Cerebras keys (D-11), per-service DB users (D-16).
Back up `TEXT_AES_KEY_BASE64` and `INDICATOR_HMAC_PEPPER` offline. Losing them makes stored text unreadable and breaks existing scam markers.

## 3. Local workflow

| Step | Command | Expected |
|---|---|---|
| Backend tests | `backend/mvnw -B -f backend/pom.xml verify` | Green (Docker must be running for Testcontainers) |
| Start | `docker compose up -d --build` | All containers `healthy` (`docker compose ps`) |
| Open | `http://localhost` | Angular app; "Sign in with Google" works |
| App logs | `tail -f logs/offershield.log` | `requestId`, `class.method` and duration only; no personal data |
| Kafka topics | `docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list` | `submission.created`, `analysis.completed` + 2 `.DLT` |
| Peek an event | `… kafka-console-consumer.sh --topic submission.created --from-beginning --max-messages 1` | IDs and metadata; no letter text |
| Frontend dev | `cd frontend && npx ng serve` with a proxy to `http://localhost` | Hot reload; full checks still go through `http://localhost` |
| Caddyfile edit | `docker compose restart caddy` | The Caddyfile is bind-mounted; Caddy does not auto-reload |
| Reset | `docker compose down -v` | **Deletes local data.** Use only on purpose |

## 4. Oracle Cloud runbook (PRD §16.3; facts V-14, V-15, V-16, V-18)

Beginner walkthrough of these steps, with exact commands: [docs/deploy/oci-step-by-step.md](../deploy/oci-step-by-step.md).

1. **Account and VM:**
   - Sign up; the card is for identity only, and nothing is charged on Always Free. Choose the home region carefully (Mumbai or Hyderabad).
   - Create an **Ubuntu arm64** VM on **VM.Standard.A1.Flex** with your SSH key. 2 OCPU / 12 GB is the max; 1 OCPU / 6 GB is also enough for this app.
   - If you get "out of capacity", retry later.
2. **Firewall (two layers):**
   - VCN security list: allow TCP 80 and 443.
   - On the VM: insert ACCEPT rules for 80/443 **before** the final REJECT rule in iptables, then `sudo netfilter-persistent save`. Do this **before installing Docker** (step 3); a later save would also store Docker's own rules, which Docker re-creates at every start.
   - **Don't enable UFW** (Oracle warns it can break booting).
   - Check with `sudo iptables -L INPUT -n --line-numbers`.
3. **Docker:** install Docker Engine + the Compose plugin from Docker's apt repo (arm64), then `docker run --rm hello-world`.
4. **Code and secrets:** `git clone`; create `.env` on the VM with `chmod 600`. Never commit it. Create the log folder for the non-root app user (uid 10001 in `backend/Dockerfile`), or file logging is silently lost: `mkdir -p logs && sudo chown 10001:10001 logs`.
5. **Domain:** create a free DuckDNS subdomain pointing at the VM's public IP.
6. **Build and start:**
   - `docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build`. The build runs on the VM, so it's arm64.
   - Check first: `docker manifest inspect <image> | grep arm64` for each base image.
7. **HTTPS:** Caddy gets the certificate automatically; port 80 must be reachable.
8. **Google:**
   - Add the production redirect URI.
   - Move the consent screen from **Testing** (100-user cap) to **In production**.
   - Scopes `openid`, `email` and `profile` don't need a verification review; brand verification only matters for showing a logo.
9. **Idle rule:** Oracle reclaims Always Free VMs only if CPU, network **and** memory all stay under 20 % for 7 days. The idle stack uses about 2 GB including the OS (containers ≈ 1.6 GB, measured locally 2026-11-18). That is about a third of a 6 GB VM, but may be under 20 % of a 12 GB VM, so the guide recommends 1 OCPU / 6 GB (+ 2 GB swap for builds); on 12 GB, check `free -h` after the first days.

## 5. Provider checks at set-up (record the results in 01 §6)

| Provider | Check | Pass condition |
|---|---|---|
| Groq (V-24, V-07) | Sign up from India; models listed; limits page; ZDR on | Works, **or** it's recorded that it doesn't (the app then runs rule-only) |
| Cloudinary (V-10) | PDFs are uploaded with delivery type `authenticated` (2026-11-18). Request the original and a derived render without a signature (e.g. `…/image/authenticated/<public_id>.pdf`, `…/image/authenticated/pg_1/<public_id>.jpg`); open a signed `privateDownload` link. Optional extra hardening: Settings → Security → "Strict transformations" | Unsigned URLs refused (401); the signed link returns the PDF. Checked 2026-11-18 (05 §7) |
| Gmail (V-13) | An app-password email to yourself via `smtp.gmail.com:587` | Delivered (it may land in spam) |
| Google (V-12) | Login via each redirect URI | Works |
| Flyway (V-25) | P1-03 migration on `mysql:8.4` | "Successfully applied" |
| Images (V-15) | `docker manifest inspect` | arm64 present for all |

## 6. Operational checks

| Check | How | When | If bad |
|---|---|---|---|
| Health | `docker compose ps` | Daily / after a deploy | `docker compose logs <svc>`; `logs/offershield.log` |
| Memory | `docker stats --no-stream` | After a deploy and under load | Adjust M-09 limits |
| Disk | `df -h`; log rotation (Docker json-file + logback 14 days) | Weekly | Prune images; check log rotation |
| Stuck submissions | `SELECT COUNT(*) FROM submission WHERE status='QUEUED' AND created_at < NOW() - INTERVAL 10 MINUTE` | Daily | Look at the DLT and the logs for that `requestId` |
| DLT backlog | `kafka-console-consumer.sh --topic submission.created.DLT --from-beginning --timeout-ms 5000`; `grep "event dead-lettered" logs/offershield.log` (each line carries `deadLettersOnTopic=N`, the Micrometer counter `offershield.kafka.dead.letters` since startup, P13) | Daily | Fix the cause; re-drive manually if safe |
| Retention ran | `SELECT COUNT(*) FROM submission WHERE expires_at < NOW() AND status <> 'DELETED'` → 0 (after 03:30 IST), and one `retention run: …` line a day in `logs/offershield.log` with `failedSteps=0 failedBatches=0` | Daily | Check the job log (`retention step failed step=…`, `retention batch skipped step=…`) |
| Startup misconfiguration | `grep -E "ai not configured\|pdf storage not configured" logs/offershield.log` (WARN at startup, P13) | After a deploy | Set `GROQ_API_KEYS` / `CLOUDINARY_URL` in `.env` and restart the app |
| PDFs pending deletion | `SELECT COUNT(*) FROM submission WHERE status = 'DELETED' AND cloudinary_public_id IS NOT NULL` → 0 | Daily | Cloudinary errors (`pdf delete failed` / `storage not configured` in the log); the next run retries (P11) |
| AI quota | "model exhausted" lines in the logs; Groq console | During a pilot | Expected (D-11); reports still arrive |
| Gmail quota | SMTP 4xx in the logs | During a pilot | Wait 1–24 h |
| TLS | Caddy logs | Monthly | Port 80 must stay open |
| Backups | **Not chosen for v1** (K-08). Documented limitation | — | Can be added later |

## 7. Post-deploy verification (M8)

1. `https://<domain>` loads with a valid certificate; S-08 headers are present, including HSTS.
2. On the VM, `sudo ss -tlnp` shows only 22 (sshd), 80 and 443 public. From another machine, `nc -zv <ip> 8080` fails (Windows: `Test-NetConnection <ip> -Port 8080` → `TcpTestSucceeded : False`; also 3306 and 9092).
3. A non-test Google account signs in from a phone; the cookie matches S-01.
4. J1 works on the phone; J3/J4 work with demo data (P14-03).
5. `docker stats` stays within the M-09 limits after J1–J4.
6. Everything is recorded in [05 §7](05-testing-and-security-plan.md), with "executed" and "passed" kept separate.
