# Quickstart — Minimal Hardware, Single Command

## One-Command Run (Linux/macOS/Windows)

```bash
# Linux/macOS/Git Bash
./run.sh

# Windows
run.bat
```

Builds automatically if needed, starts on port 8000, opens browser.

## Minimal Hardware Requirements

| Resource | Minimum | Recommended |
|----------|---------|-------------|
| CPU | 1 vCPU | 2+ vCPU |
| RAM | 1 GB | 2 GB |
| Disk | 500 MB | 1 GB |
| Java | JDK 25+ | JDK 25 |

## Profiles

- `dev` (default) — H2 in-memory DB, no PostgreSQL needed
- `default` — PostgreSQL (requires `POSTGRES_*` env vars or `.env`)

```bash
./run.sh --profile default
# or with Docker:
cp .env.example .env  # edit credentials
./mvnw clean package -DskipTests -Pslim
docker compose -f docker-compose.lite.yml up
```

## Useful Options

```bash
./run.sh --port 9000        # different port
./run.sh --no-browser       # headless
./run.sh --help             # show options
```

### Windows caveats

On Windows use the real `cmd.exe` launcher, not the shell-script shortcuts. Use
**`start.bat`**, not `run.bat`.

```bat
start.bat                  rem builds if needed, serves on :8000, waits for /health, opens the browser
start.bat --no-build       rem relaunch the existing jar (fast)
start.bat --port 9000      # different port
start.bat --no-browser     # headless
start.bat --doctor         # report JDK, port, jar and OpenCV engine state, then exit
start.bat --help           # show options
```

**Windows caveats and a known old-runner issue:**

- Use a real **Windows console** (`cmd.exe` or PowerShell). Do not launch these
  with Git Bash or WSL — those do not run `.bat` files the same way, and names
  like `start` clash with built-in commands.
- Install the **full JDK 25+**. Do **not** use the slim
  Twitter-style "vscode-jdk" extensions or WSL-only toolchains; `start.bat` and
  `run.bat` need a stock JDK at `C:\Program Files\Java\...` or
  `C:\Program Files\Eclipse Adoptium\...` to find it and to load the OpenCV
  natives.
- The OpenAPI docs and the Swagger UI work from the staging/release server, but
  calling the face/end-point APIs from a browser is only safe from
  `http://localhost` — a remote browser receives a warning that tells it not to
  open the camera. This service, just like `./run.sh`, is meant to be started
  locally and behind a browser for the first run to trigger the browser camera
  permission, so prefer a local copy.

**`run.bat` used to be broken (fixed in this release — use `start.bat` only):**
the old `run.bat` could mis-detect the Java version on modern JDKs (it rejected
JDK 21+) and used `timeout /t 1` for its health wait, which fails on a typical
Windows setup because Git for Windows shadows the real `timeout.exe`. Both
launchers are now correct, but if you have shell scripts pinned to `run.bat`,
switch them to `start.bat` so a future release does not leave you browsing a
stale copy.

## Docker (Production)

```bash
# Build slim jar once
./mvnw clean package -DskipTests -Pslim

# Run with Docker (needs .env with POSTGRES_PASSWORD, etc.)
docker compose -f docker-compose.lite.yml up -d
```

Image size: ~90 MB layers (vs ~294 MB full). Runs as non-root user.

## Test

```bash
./mvnw test                    # all tests
./mvnw test -Dtest=PostgresSchemaTest  # DB schema gate
```

## Environment Variables

| Variable | Required | Default |
|----------|----------|---------|
| `POSTGRES_PASSWORD` | PostgreSQL only | — |
| `POSTGRES_APP_PASSWORD` | PostgreSQL only | — |
| `MOSIP_AUTH_BOOTSTRAP_USERNAME` / `MOSIP_AUTH_BOOTSTRAP_PASSWORD` | Only on a database with no accounts: creates the first supervisor | — |
| `MOSIP_OPERATOR_API_KEY` | For the operator role (min 16 chars) | blank = operator routes refuse everyone |
| `MOSIP_AUDIT_HMAC_SECRET` | Recommended | — |
| `MIN_CHALLENGE_WINDOW_MS` | No | 15000 |

Generate keys:
```bash
export MOSIP_AUTH_BOOTSTRAP_USERNAME=supervisor
export MOSIP_AUTH_BOOTSTRAP_PASSWORD=$(openssl rand -base64 24)
export MOSIP_OPERATOR_API_KEY=$(openssl rand -hex 32)
export MOSIP_AUDIT_HMAC_SECRET=$(openssl rand -hex 32)
```
