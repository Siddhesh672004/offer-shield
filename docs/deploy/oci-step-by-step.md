# Deploy OfferShield on Oracle Cloud (Always Free): step by step

This guide is for someone deploying to the cloud for the first time. It follows the runbook in
[06 §4](../plan/06-deployment-and-verification.md) (D-23) and ends with the 06 §7 checklist.
Oracle's and Google's websites change their menus often. Where this guide says **"look for"**, the exact label may be slightly different.

**What you end up with:** one small Ubuntu server at Oracle running five Docker containers: Caddy, the Angular frontend, the app, MySQL and Kafka. The whole app is reachable at `https://<name>.duckdns.org`, and only Caddy is open to the internet.

```text
phone / laptop ──HTTPS──▶ Oracle firewall (80, 443) ──▶ VM firewall (iptables)
                           ──▶ Caddy (certificate, routing) ──▶ frontend (nginx) / app (Spring Boot)
                                                                           └──▶ MySQL, Kafka (no outside access)
```

**Placeholders used below. Replace them everywhere:**

| Placeholder | Meaning | Example |
|---|---|---|
| `<name>` | your DuckDNS sub-domain | `offershield-demo` |
| `<VM_IP>` | the VM's public IP address | `140.238.1.2` |

**Where to type commands:**
- **Laptop:** use **Git Bash**, which comes with Git for Windows. Where a step says **PowerShell**, use PowerShell instead.
- **VM:** type the command after you have connected with `ssh` (step 4).

**Time:** about 2–3 hours the first time. The Oracle account can take from a few minutes to a few hours to be approved, and the first build takes 15–40 minutes.

**Never cut these (security invariants in [CLAUDE.md](../../CLAUDE.md)):**
- Only ports 22 (SSH), 80 and 443 are opened. Never open 8080, 3306 or 9092.
- Never enable UFW.
- Never put `.env` anywhere public.

---

## 1. Create the Oracle Cloud account

1. Go to <https://www.oracle.com/cloud/free/> and click **Start for free**.
2. Fill in your country (India), name and email, then confirm the email Oracle sends you.
3. Choose a password and a **Cloud Account Name**. This is your "tenancy": you type it every time you sign in, so write it down.
4. **Home Region: choose `India West (Mumbai)` or `India South (Hyderabad)`.** This cannot be changed later, and Always Free servers can only be created in the home region.
5. Enter your address and mobile number.
6. Add a card. **The card is for identity only.** Oracle may place a small temporary hold that is released, and nothing is charged on Always Free.
   - If the card is refused, check that online and international transactions are enabled for it. Many debit cards are refused; a credit card usually works.
7. **Do not click "Upgrade to Pay As You Go".** We use free services only.
8. Wait for the email **"Your Oracle Cloud account is fully provisioned"**. Then sign in at <https://cloud.oracle.com> with your Cloud Account Name, email and password.
   - Oracle will ask you to set up 2-step verification (for example the Oracle Mobile Authenticator app). Do it.
9. *(Optional, recommended)* Set up a spending alarm:
   - Menu **☰ → Billing & Cost Management → Budgets → Create Budget**.
   - Set the amount to 1 (your currency) and alert at 100 %. If anything ever starts to cost money, you get an email.

## 2. Make an SSH key on your laptop

An SSH key is how you log in to the VM. It has two parts:
- the **private** key, which stays on your laptop and is never shared
- the **public** key (`.pub`), which you give to Oracle

**Laptop (Git Bash):**

```bash
mkdir -p ~/.ssh
ssh-keygen -t rsa -b 4096 -f ~/.ssh/oci_offershield -C "offershield-oci"
```

- It asks for a passphrase. Choose one and remember it; it protects the key if your laptop is stolen.
- We use an RSA key because Oracle's console accepts it everywhere.
- Show the **public** key so you can copy it in step 3. This part is fine to paste into Oracle.

```bash
cat ~/.ssh/oci_offershield.pub
```

## 3. Create the VM

1. Open Menu **☰ → Compute → Instances**.
2. Click **Create instance**. The page is a wizard; look for these parts:
   - **Name:** `offershield`.
   - **Image and shape:**
     1. Click **Change shape → Virtual machine → Ampere → `VM.Standard.A1.Flex`**. It shows an "Always Free-eligible" label.
     2. **Recommended: 1 OCPU and 6 GB memory.** This is easier to get when Oracle is busy, and the app keeps the VM "in use" for Oracle's idle rule (see step 15.5).
     3. 2 OCPUs and 12 GB is the free maximum. It also works and builds faster.
     4. Then **Change image → Ubuntu → Canonical Ubuntu 24.04**. Choose the normal image, not "Minimal". The build name must say **aarch64** (that is arm64). The console picks it automatically once the shape is Ampere.
   - **Networking:** "Create new virtual cloud network" and "Create new public subnet" (the defaults). Make sure **Assign a public IPv4 address** is on.
   - **Add SSH keys:** choose **Paste public keys** and paste the whole line from `oci_offershield.pub`.
   - **Boot volume:** keep the default size. Always Free includes up to 200 GB of disk.
3. Click **Create**. Wait until the state is **Running** (green).
4. On the instance page, copy the **Public IP address**. This is `<VM_IP>`.

**"Out of capacity" / "Out of host capacity"?** This is common, and nothing is wrong with your account. Try again later (early morning IST often works), or try 1 OCPU / 6 GB if you asked for more.

## 4. Connect to the VM and update it

**Laptop (Git Bash).** Create a short name for the server, so you can type `ssh offershield` instead of the full command. Replace `<VM_IP>` with the real IP **before** you press Enter:

```bash
cat >> ~/.ssh/config <<'EOF'
Host offershield
  HostName <VM_IP>
  User ubuntu
  IdentityFile ~/.ssh/oci_offershield
EOF
```

Then connect:

```bash
ssh offershield
```

The first time, it asks `Are you sure you want to continue connecting?` → type `yes`. The prompt changes to something like `ubuntu@offershield:~$`. You are now **on the VM**.

**VM:**

```bash
uname -m                       # must print: aarch64
sudo apt-get update && sudo apt-get -y upgrade
sudo reboot                    # only if the upgrade says a restart is required; then wait 1 minute and ssh again
```

## 5. Firewall layer 1: Oracle's network (VCN security list)

Oracle blocks everything except SSH by default. Open 80 (HTTP, needed for the certificate) and 443 (HTTPS).

1. Open Menu **☰ → Networking → Virtual cloud networks**, then click your VCN (named like `vcn-2026…`).
2. Look for the **Security** tab (older screens: **Security Lists** under "Resources"). Click **Default Security List for vcn-…**.
3. Look for **Security rules / Ingress Rules → Add Ingress Rules**. Add two rules:

   | Source CIDR | IP Protocol | Destination Port Range | Description |
   |---|---|---|---|
   | `0.0.0.0/0` | TCP | `80` | HTTP (Caddy, certificate) |
   | `0.0.0.0/0` | TCP | `443` | HTTPS |

   Leave "Source Port Range" empty (it means "All").
4. Keep the existing port 22 rule.

**Do not add rules for 8080, 3306 or 9092.**

## 6. Firewall layer 2: iptables on the VM (do this before installing Docker)

Oracle's Ubuntu image has its own firewall rules that reject everything except SSH. Add 80 and 443 **above** the final REJECT line.

**Never use `ufw`.** Oracle warns that enabling UFW on its images can lock you out or stop the VM from booting.

**VM:**

```bash
sudo iptables -L INPUT -n --line-numbers
```

You will see something like this (yours may differ):

```text
num  target  prot opt source     destination
1    ACCEPT  all  --  0.0.0.0/0  0.0.0.0/0   state RELATED,ESTABLISHED
2    ACCEPT  icmp --  0.0.0.0/0  0.0.0.0/0
3    ACCEPT  all  --  0.0.0.0/0  0.0.0.0/0
4    ACCEPT  udp  --  0.0.0.0/0  0.0.0.0/0   udp spt:123
5    ACCEPT  tcp  --  0.0.0.0/0  0.0.0.0/0   state NEW tcp dpt:22
6    REJECT  all  --  0.0.0.0/0  0.0.0.0/0   reject-with icmp-host-prohibited
```

Use **the number of the REJECT line** (6 in this example) in both commands:

```bash
sudo iptables -I INPUT 6 -p tcp -m state --state NEW --dport 80 -j ACCEPT
sudo iptables -I INPUT 6 -p tcp -m state --state NEW --dport 443 -j ACCEPT
sudo netfilter-persistent save        # keeps the rules after a reboot
sudo iptables -L INPUT -n --line-numbers
```

Now 443 and 80 must be listed **before** the REJECT line.
- If `netfilter-persistent` is "command not found", run `sudo apt-get install -y iptables-persistent` and save again.
- Why before Docker? Docker adds its own rules every time it starts. If you run `netfilter-persistent save` after Docker is installed, those rules get saved too and pile up after reboots.

## 7. Add swap (recommended on the 6 GB VM)

Swap is disk space used as emergency memory. It stops the build from being killed when memory is short.

**VM:**

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h                               # "Swap:" now shows 2.0Gi
```

## 8. Install Docker

This uses Docker's official apt repository, which picks the arm64 build automatically.

**VM:**

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

sudo docker run --rm hello-world      # prints "Hello from Docker!"
docker compose version                # must be v2.24 or newer (the production file uses "!override")
```

Let your user run `docker` without `sudo`. This makes the user as powerful as root, which is fine on a server only you use. Then log out and back in:

```bash
sudo usermod -aG docker $USER
exit
```

**Laptop:** `ssh offershield` again. **VM:** `docker ps` now works without `sudo`.

Docker starts automatically after a reboot, and the containers come back on their own (`restart: unless-stopped`).

## 9. Get a free DuckDNS name

1. Go to <https://www.duckdns.org> and sign in with one of the providers at the top (for example GitHub or Google).
2. Type your sub-domain (`<name>`) and click **add domain**.
3. In **current ip**, type `<VM_IP>` and click **update ip**.
4. Keep the DuckDNS **token** private. We don't need it, because Oracle's public IP stays the same while the VM exists. If you ever delete and re-create the VM, update the IP here.

Check it from the **laptop (PowerShell):**

```powershell
Resolve-DnsName <name>.duckdns.org     # IPAddress must be <VM_IP>
```

## 10. Put the code on the VM

**Option A: zip upload (use this now).** The current code is not pushed to GitHub (it is uncommitted on `phase/p05-p13`), so copy your working folder.
- The archive contains only the files git would track.
- `.env`, `logs/`, `node_modules/`, `target/` and `dist/` are left out automatically by `.gitignore`.

**Laptop (Git Bash):**

```bash
cd /e/LTIM/OfferShield
git ls-files -co --exclude-standard -z | tar --null -T - -czf ~/offershield.tgz
tar -tzf ~/offershield.tgz | wc -l                    # several hundred files (about 650 on 2026-11-18)
tar -tzf ~/offershield.tgz | grep -E '(^|/)\.env$'    # must print NOTHING (.env.example is fine)
scp ~/offershield.tgz offershield:~/
```

**VM:**

```bash
mkdir -p ~/offershield
tar -xzf ~/offershield.tgz -C ~/offershield
cd ~/offershield && ls                # docker-compose.yml, docker-compose.prod.yml, Caddyfile, backend, frontend …
```

**Option B: `git clone` (later, once the code is pushed).**

```bash
git clone https://github.com/<you>/OfferShield.git ~/offershield
```

If the repository is private, GitHub asks for your username and a **personal access token**, not your password.

## 11. Create `.env` on the VM

`.env` holds every secret. It lives only on your laptop and on the VM:
- never commit it
- never paste it into chats, issues or screenshots
- never print it on screen

The simplest way is to copy your laptop's `.env` over SSH (encrypted) and change **two lines**.

**Laptop (Git Bash):**

```bash
scp /e/LTIM/OfferShield/.env offershield:~/offershield/.env
```

**VM:**

```bash
cd ~/offershield
chmod 600 .env                         # only you can read it
sed -i 's/\r$//' .env                  # removes Windows line endings, if any
nano .env
```

In `nano`, change only these two lines:

```text
APP_DOMAIN=<name>.duckdns.org
APP_BASE_URL=https://<name>.duckdns.org
```

- `APP_DOMAIN` is the bare name: no `https://` and no `/` at the end.
- Save with **Ctrl+O**, then **Enter**. Exit with **Ctrl+X**.

Everything else stays as on your laptop:
- `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET`
- `ADMIN_EMAILS` (your Google email)
- `TEXT_AES_KEY_BASE64`, `INDICATOR_HMAC_PEPPER`
- `MYSQL_ROOT_PASSWORD`, `DB_PASSWORD`
- `KAFKA_BOOTSTRAP=kafka:9092`
- `GROQ_API_KEYS`, `AI_CHAIN`
- `CLOUDINARY_URL`, `SMTP_USERNAME`, `SMTP_APP_PASSWORD`

A blank AI, Cloudinary or SMTP value only switches that feature off ("AI unavailable", no PDF kept, no emails).

*(Optional, stronger)* The production database starts empty, so production can use its own passwords and keys instead of the laptop's. Do this **before the first start**:
- Generate each value with `openssl rand -base64 24` (the passwords) or `openssl rand -base64 32` / `48` (the AES key and pepper, see [06 §2](../plan/06-deployment-and-verification.md)).
- MySQL keeps the passwords it was first started with, so don't change `MYSQL_ROOT_PASSWORD` or `DB_PASSWORD` afterwards.
- Back up `TEXT_AES_KEY_BASE64` and `INDICATOR_HMAC_PEPPER` offline, for example in a password manager. Losing them makes stored letters unreadable.

Check your work without showing any secret:

```bash
ls -l .env                                     # starts with -rw-------
grep -E '^(APP_DOMAIN|APP_BASE_URL)=' .env     # shows only these two lines
```

*(Optional)* To type shorter commands later, add this line to `.env`. Then `docker compose …` always uses both files, and you can drop the two `-f` options below:

```text
COMPOSE_FILE=docker-compose.yml:docker-compose.prod.yml
```

## 12. Create the logs folder

The app runs as a non-root user (uid 10001, see `backend/Dockerfile`). Without this step, file logging is silently lost.

**VM:**

```bash
cd ~/offershield
mkdir -p logs && sudo chown 10001:10001 logs
```

## 13. Google sign-in for production

Open <https://console.cloud.google.com> and select the project that holds your OAuth client (top bar).

1. **Redirect URI:**
   1. Look for **Google Auth Platform → Clients** (older menu: **APIs & Services → Credentials → OAuth 2.0 Client IDs**) and open your **Web application** client.
   2. Under **Authorized redirect URIs** click **Add URI** and enter exactly:
      ```text
      https://<name>.duckdns.org/login/oauth2/code/google
      ```
   3. Keep `http://localhost/login/oauth2/code/google` for local testing. Click **Save**. Google can take a few minutes to apply it.
2. **Publish the app:**
   1. Look for **Google Auth Platform → Audience** (older menu: **OAuth consent screen**).
   2. Under **Publishing status: Testing**, click **Publish app** and confirm. The status becomes **In production**.
   3. In "Testing" only your listed test users (at most 100) can sign in; "In production" lets any Google account sign in.
   4. OfferShield asks only for `openid`, `email` and `profile`, so no verification review is needed.
   5. Don't upload a logo: a logo triggers brand verification.
   6. If the console asks for an authorized domain, add `<name>.duckdns.org`.

## 14. Start OfferShield

**VM:**

```bash
cd ~/offershield
docker compose -f docker-compose.yml -f docker-compose.prod.yml config -q && echo "config OK"
```

- `config OK` means `.env` and both files fit together.
- An error like `required variable APP_DOMAIN is missing a value` means `.env` is missing that line. The message names the variable, never its value.

*(Optional)* Check that every base image has an arm64 build. This was already checked on 2026-11-18; every line should end in a number of 1 or more:

```bash
for i in caddy:2.11.7-alpine mysql:8.4 apache/kafka:4.3.1 eclipse-temurin:21-jdk eclipse-temurin:21-jre \
         node:24.17.0-alpine nginxinc/nginx-unprivileged:1.31.7-alpine; do
  echo "$i: $(docker manifest inspect "$i" | grep -c '"arm64"')"; done
```

Build and start. The images are built **on the VM**, so they are arm64. The first time takes 15–40 minutes.

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.yml -f docker-compose.prod.yml ps
```

Wait until all five containers say **healthy**; the app takes about 1–2 minutes after the build. Then watch Caddy get the certificate:

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml logs caddy | grep -i certificate
```

Look for `certificate obtained successfully`. If it shows errors, see **Troubleshooting**.

Open `https://<name>.duckdns.org` in your browser.

## 15. Check it: the post-deploy checklist ([06 §7](../plan/06-deployment-and-verification.md))

Record every result in [05 §7](../plan/05-testing-and-security-plan.md), with **Executed** and **Passed** in separate columns.

**15.1 HTTPS and security headers (S-08).** **Laptop (Git Bash):**

```bash
curl -sI https://<name>.duckdns.org/
curl -sI https://<name>.duckdns.org/api/ping
curl -sI http://<name>.duckdns.org/
curl -s  https://<name>.duckdns.org/actuator/env | head -c 120; echo
```

Expected:
- `/`: `HTTP/2 200` (or `HTTP/1.1 200`), `strict-transport-security: max-age=31536000`, `content-security-policy: default-src 'self'; …`, `x-content-type-options: nosniff`, `referrer-policy: no-referrer`, `x-frame-options: DENY`.
- `/api/ping`: `HTTP/2 200` with `strict-transport-security` and the same CSP (set by the app).
- `http://`: a `308` redirect to `https://…`.
- `/actuator/env`: the Angular page's HTML, not JSON. Actuator is not reachable from outside (S-09).
- The browser shows the padlock with a valid certificate.

**15.2 Only 22, 80 and 443 are open (S-02).**

On the **VM:**

```bash
sudo ss -tlnp
```

Lines on `0.0.0.0` / `[::]` / `*` must be only:
- `:22` (sshd)
- `:80` and `:443` (docker-proxy)

Lines on `127.0.0.53` / `127.0.0.54` (systemd-resolved) are local-only and fine.

From the **laptop (PowerShell)**:

```powershell
Test-NetConnection <VM_IP> -Port 443     # TcpTestSucceeded : True
Test-NetConnection <VM_IP> -Port 8080    # TcpTestSucceeded : False
Test-NetConnection <VM_IP> -Port 3306    # False
Test-NetConnection <VM_IP> -Port 9092    # False
```

**15.3 Sign-in from a phone and the cookie (S-01).**
1. Sign in from a phone with a Google account that is **not** one of your test users.
2. On a desktop browser, sign in and open DevTools (**F12**) → **Application** → **Cookies** → `https://<name>.duckdns.org`.
3. `JSESSIONID` must show **HttpOnly** ✓, **Secure** ✓ and **SameSite = Lax**.

**15.4 Journeys with fake letters only ([05 §5](../plan/05-testing-and-security-plan.md)).**
- **J1 on the phone:** sign in → Continue on my own → paste a fake letter → report → My submissions.
- **Demo data (P14-03):** use your own fake letters, for example the ones in `backend/src/test/resources/fixtures/letters/` (`deposit-scam.txt`, `genuine-offer.txt`, …). Never use a real person's letter.
- **J3** (scam report → "Reported as a scam by 1 user (not verified)") and **J4** (admin confirms → "Confirmed scam" → Revert) need three Google accounts. One of them must be in `ADMIN_EMAILS`.
- **Done when** a friend signs in from their phone and gets a report.

**15.5 Known companies.** Sign in with the admin account and open **Admin → Known companies**. Add the 12 companies in [known-companies.md](known-companies.md) (name and domains exactly as listed; the same list as JSON is in [known-companies.json](known-companies.json)). Until you add them, the look-alike checks C3/C4 stay "not applicable".

**15.6 Memory (M-09).** **VM:**

```bash
docker stats --no-stream
free -h
```

Each container's **MEM USAGE** must stay below its **LIMIT**:

| Container | Limit |
|---|---|
| app | 1 GiB |
| mysql | 1.5 GiB |
| kafka | 1 GiB |
| caddy | 128 MiB |
| frontend | 128 MiB |

**Oracle's idle rule:** Oracle may reclaim (stop) an Always Free VM if, for 7 days, CPU, network **and** memory use all stay under 20 %. The running stack uses about 2 GB including Ubuntu (measured locally on 2026-11-18: about 1.6 GB for the five containers when idle):
- On the 6 GB VM that is about a third, so it stays above the line.
- On a 12 GB VM it may be under 20 %, so check `free -h` ("used") after a few days.

**15.7** Write everything into 05 §7, and keep running the daily checks in [06 §6](../plan/06-deployment-and-verification.md).

## 16. Day-to-day

Run these **on the VM** in `~/offershield`. Add `-f docker-compose.yml -f docker-compose.prod.yml` after `docker compose`, unless you added the `COMPOSE_FILE` line to `.env`.

| Task | Command |
|---|---|
| Status | `docker compose ps` |
| App log (no personal data, D-27) | `tail -f logs/offershield.log` |
| A container's output | `docker compose logs --tail 100 app` (or `caddy`, `mysql`, `kafka`, `frontend`) |
| Restart one container | `docker compose restart app` |
| After editing the Caddyfile | `docker compose restart caddy` |
| Deploy new code | Repeat step 10 (Option A: the archive overwrites the code; `.env` and `logs/` stay), then `docker compose up -d --build` |
| Stop everything (data is kept) | `docker compose down` |
| Disk space | `df -h` and `docker system df`; free space with `docker image prune` and `docker builder prune` |
| OS updates | `sudo apt-get update && sudo apt-get -y upgrade` (Ubuntu also installs security updates by itself) |

**Never run `docker compose down -v`.** The `-v` deletes the database volume, and v1 has **no backups** (K-08).

## 17. Troubleshooting

| Problem | Likely cause and fix |
|---|---|
| "Out of capacity" when creating the VM | Oracle has no free Ampere machines right now. Retry later, or choose 1 OCPU / 6 GB |
| `Permission denied (publickey)` | The user must be `ubuntu`, and the key must be `~/.ssh/oci_offershield` (check `~/.ssh/config`) |
| `ssh` hangs | The VM has no public IP, or the port 22 rule was removed from the security list |
| Caddy log: `challenge failed`, `timeout during connect`, `connection refused` | Port 80 isn't reachable. Check the VCN rule (step 5), the iptables order (step 6: ACCEPT must be **above** REJECT) and that `<name>.duckdns.org` points to `<VM_IP>` (step 9). Caddy retries by itself; after a fix, `docker compose restart caddy` |
| Browser: "Your connection is not private" right after the start | The certificate isn't issued yet. Wait a minute and check the Caddy log |
| Google: `Error 400: redirect_uri_mismatch` | The URI in step 13 must be exactly `https://<name>.duckdns.org/login/oauth2/code/google` (https, no slash at the end). Wait a few minutes after saving |
| Google: "Access blocked … has not completed the Google verification process" / only test users can sign in | The app is still in **Testing**. Publish it (step 13.2) |
| `required variable … is missing a value` | That line is missing or empty in `.env` |
| `app` keeps restarting or is `unhealthy` | `docker compose logs --tail 100 app`. Startup messages name the missing setting but never print secrets |
| `app` log has DB "Access denied" | `DB_PASSWORD` was changed after the first start. MySQL keeps the first password |
| `logs/offershield.log` stays empty | Step 12 was skipped: `sudo chown 10001:10001 logs`, then `docker compose restart app` |
| The build is killed or very slow on 6 GB | Add swap (step 7), or build one image at a time: `docker compose build app`, then `docker compose build frontend`, then `docker compose up -d` |

## 18. Moving to another host (M-10 fallback)

There is no separate Render runbook (M-10). Render's free plan cannot run Kafka, MySQL or always-on workers (PRD §16.4).

**The fallback:** any Linux machine with about 4 GB of RAM or more (6 GB is comfortable), Docker and the Compose plugin can run the same two files:
1. Repeat steps 10–12 and 14 on that machine, plus its own firewall for ports 80 and 443.
2. Point DuckDNS at its IP.
3. Google needs no change if the domain stays the same.

The new host starts with an **empty database**. There are no backups in v1 (K-08), so reports and registry data stay behind on the old VM.
