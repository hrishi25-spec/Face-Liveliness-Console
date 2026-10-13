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
| Java | JDK 17+ | JDK 21 |

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
| `MOSIP_ADMIN_API_KEY` | For config writes | — |
| `MOSIP_OPERATOR_API_KEY` | For the operator role (min 16 chars, must differ from the admin key) | blank = operator routes refuse everyone |
| `MOSIP_AUDIT_HMAC_SECRET` | Recommended | — |
| `MIN_CHALLENGE_WINDOW_MS` | No | 15000 |

Generate keys:
```bash
export MOSIP_ADMIN_API_KEY=$(openssl rand -hex 32)
export MOSIP_OPERATOR_API_KEY=$(openssl rand -hex 32)
export MOSIP_AUDIT_HMAC_SECRET=$(openssl rand -hex 32)
```
