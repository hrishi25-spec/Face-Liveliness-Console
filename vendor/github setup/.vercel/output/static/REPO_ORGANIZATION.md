# How a repository should be organised

A repository is a map, not a dump. Someone who has never seen the project should be able to clone it, understand what it is, run it, and find the code they need — in minutes, not an afternoon.

This guide is a practical standard. Follow it unless you have a written reason not to.

## The standard in one page

1. **The root is a lobby.** Only files a new contributor needs in the first ten minutes live there.
2. **One place for each kind of thing.** Source, tests, docs, scripts, assets, and generated output never share a folder.
3. **Name for the reader.** Folders describe *what the software does*, not *what kind of file it is*.
4. **Colocate what changes together.** A feature's UI, logic, and tests sit next to each other.
5. **Generated files are not source.** Build output, coverage, caches, and vendor installs are gitignored.
6. **Secrets never enter git.** Not even once. Not even encrypted in a pinch.
7. **The README is the front door.** It answers what, why, how to run, how to test, and where to go next.
8. **Scripts are the interface to automation.** Humans and CI call the same named commands.
9. **Depth is a smell.** More than three or four folder levels usually means the split is wrong.
10. **Document exceptions.** If the layout diverges, say so in the README. Do not leave people guessing.

If you do only these ten, the rest of this document is commentary.

## Why organisation matters

A messy repo taxes every future change:

- Onboarding stretches from an hour to a week.
- Reviews stall because reviewers cannot find the seam.
- CI becomes a pile of special cases.
- Ownership blurs — and unowned code rots.

Organisation is not aesthetics. It is how a team scales without a tour guide.

## Principles

### One obvious place

If two reasonable people would put the same file in two different folders, the scheme has failed. Pick a rule, write it down, apply it everywhere.

### The root is a lobby, not a junk drawer

A stranger's first view of the repo is `ls` at the root. That listing should read like a table of contents, not a desktop.

### Name for the reader, not the author

`billing/`, `auth/`, and `catalog/` tell a story. `helpers/`, `common/`, `misc/`, `new/`, and `stuff/` do not.

### Colocate change

If a bugfix always touches three folders, those three folders should have been one.

### Hide the machine's mess

`dist/`, `build/`, `.next/`, `coverage/`, `.turbo/`, `node_modules/`, `__pycache__/`, and `target/` are artefacts. They belong in `.gitignore`, not in history.

### Make the happy path obvious

The default way to install, run, test, lint, and ship should be three commands or fewer, documented at the top of the README.

## The root

Keep the root small and stable. A healthy root looks like this:

```text
.
├── README.md
├── LICENSE
├── CHANGELOG.md
├── CONTRIBUTING.md
├── CODE_OF_CONDUCT.md
├── SECURITY.md
├── package.json          # or pyproject.toml / go.mod / Cargo.toml
├── package-lock.json     # the lockfile for your package manager
├── tsconfig.json         # language/tool config lives at the root
├── .gitignore
├── .editorconfig
├── .nvmrc                # or .python-version / .tool-versions
├── Dockerfile            # only if you ship a container
├── src/
├── tests/                # only if tests are not colocated
├── docs/
├── scripts/
├── public/               # static assets served as-is
└── .github/
```

### What belongs at the root

| File | Purpose |
| --- | --- |
| `README.md` | What this is, why it exists, how to run it |
| `LICENSE` | The licence. Unlicensed work is unusable. |
| `CHANGELOG.md` | User-facing changes, newest first |
| `CONTRIBUTING.md` | How to propose work, review, and release |
| `CODE_OF_CONDUCT.md` | How people treat each other |
| `SECURITY.md` | How to report a vulnerability |
| `package.json` / `pyproject.toml` / `go.mod` / `Cargo.toml` | The project manifest |
| Lockfile | Reproducible installs. Always commit it for apps. |
| Tool config | `tsconfig.json`, `eslint.config.js`, `vitest.config.ts`, and similar |
| `.gitignore` | What git must never see |
| `.editorconfig` | Cross-editor basics: charset, indentation, newlines |
| Runtime pin | `.nvmrc`, `.python-version`, or `.tool-versions` |
| `Dockerfile` / `compose.yaml` | Only if this repo actually ships or develops in containers |

### What does not belong at the root

- Scratch notes, meeting dumps, and personal TODO files
- Zipped backups and `old/` folders
- Editor folders except a shared `.vscode/` or `.idea/` the team has agreed to
- Environment files with real secrets (`.env`, `.env.local`, credentials JSON)
- Build output
- One-off scripts that are not part of the project's interface
- Screenshots of the app taken during development

If a file is useful, give it a real home (`docs/`, `scripts/`, `assets/`). If it is not useful, delete it.

### README as the front door

A README that earns its place answers, in this order:

1. **Name and one-sentence purpose.**
2. **Status.** Stable, experimental, deprecated — plus a link if something replaces it.
3. **Requirements.** Language version, system packages, services.
4. **Quick start.** Clone, install, configure, run. Copy-pasteable.
5. **Tests and quality.** The one command that must pass before a review.
6. **Project map.** A short pointer to `src/`, `docs/`, `scripts/`.
7. **How to contribute.** Link `CONTRIBUTING.md`; do not duplicate it.
8. **Licence and contact.**

Do not put architecture essays in the README. Link `docs/` instead.

## Source code

### Prefer a `src/` (or language equivalent)

A dedicated source root keeps tooling simple and stops application code from mixing with config.

| Language | Conventional source root |
| --- | --- |
| TypeScript / JavaScript | `src/` |
| Python | package directory named after the project, not a grab-bag `src` of scripts |
| Go | module root; packages as subfolders. No `src/` — that is not idiomatic Go |
| Rust | `src/` with `lib.rs` or `main.rs` |
| Java / Kotlin | `src/main/java` (or `kotlin`) as the build expects |
| Ruby | `lib/` and `app/` (Rails) |

Follow the language. Do not invent a clever layout the ecosystem's tools will fight.

### Feature folders, not type folders

Organise around *capabilities*, not file kinds.

Avoid this:

```text
src/
├── components/
├── hooks/
├── utils/
├── types/
├── services/
└── store/
```

This layout answers "what is a React component?" It does not answer "where does checkout live?" Every feature is scattered across six folders, and a change to billing means a scavenger hunt.

Prefer this:

```text
src/
├── app/                  # shell: routing, providers, global styles
├── features/
│   ├── billing/
│   │   ├── invoice-list.tsx
│   │   ├── invoice-list.test.ts
│   │   ├── pricing.ts
│   │   └── index.ts      # the public API of this feature
│   ├── auth/
│   └── catalog/
├── shared/               # truly cross-cutting primitives only
│   ├── ui/
│   ├── lib/
│   └── types/
└── styles/
```

Rules for `shared/`:

- If it is used by one feature, it is not shared. Move it back.
- If it is a grab-bag, it will become a junk drawer. Split by purpose (`ui/`, `lib/date/`, `lib/http/`).
- A file named `helpers.ts` or `utils.ts` at any depth is a warning.

### Public API of a folder

A feature folder should have a narrow front door — usually `index.ts` (or `__init__.py`, or the package's public files).

- Other features import from the folder, not from files inside it.
- Internal files can move without breaking the rest of the tree.
- Do not re-export everything. A barrel that dumps 40 names is not a boundary.

### Depth

If you need a path like `src/features/billing/invoices/list/components/row/cells/`, the design is wrong. Flatten. Three levels under `src/` is comfortable; four is the usual limit; five wants a reason.

### Files, not trivia

- One main idea per file. Not one function per file, and not a 2,000-line "god file".
- Name files after the thing they export: `invoice-list.tsx` exports `InvoiceList`.
- Avoid `index.ts` as a hiding place for real logic. Index files re-export; they do not implement.
- Prefer kebab-case for files in JS/TS (`invoice-list.tsx`). Match the language's convention elsewhere (`invoice_list.py`, `invoice_list.go`).

## Language-shaped trees

The same principles, applied to common stacks. Copy the one you need.

### TypeScript application

```text
.
├── src/
│   ├── routes/           # or app/, pages/ — match the framework
│   ├── features/
│   ├── shared/
│   └── styles/
├── public/
├── tests/e2e/            # end-to-end only; unit tests sit next to source
├── scripts/
├── docs/
├── package.json
├── tsconfig.json
└── vite.config.ts
```

### Python package

```text
.
├── src/
│   └── billing_service/
│       ├── __init__.py
│       ├── invoices.py
│       └── payments/
├── tests/
├── docs/
├── scripts/
├── pyproject.toml
├── README.md
└── .python-version
```

Keep the import name (`billing_service`) identical to the distribution name unless you have a hard reason not to.

### Go module

```text
.
├── cmd/
│   └── api/
│       └── main.go
├── internal/
│   ├── billing/
│   └── auth/
├── pkg/                  # only for libraries other modules should import
├── api/                  # OpenAPI, proto, generated clients
├── scripts/
├── go.mod
└── README.md
```

`internal/` is not optional taste — the compiler enforces it. Put nothing in `pkg/` unless an external module should import it.

### Rust

```text
.
├── src/
│   ├── main.rs
│   ├── lib.rs            # if this is also a library
│   └── billing/
├── tests/                # integration tests
├── benches/
├── examples/
├── Cargo.toml
└── rust-toolchain.toml
```

### Monorepo

A monorepo is a collection of packages with a single history, not a licence to mix them.

```text
.
├── README.md             # the workspace map
├── package.json          # workspace root: scripts, packageManager, private: true
├── pnpm-workspace.yaml   # or Cargo workspace / go.work / uv workspace
├── turbo.json            # or nx.json, moon.yml — if you use an orchestrator
├── packages/
│   ├── ui/               # shared library
│   ├── config/           # shared tsconfig / eslint
│   └── billing-client/
├── apps/
│   ├── web/
│   └── api/
├── docs/
├── scripts/
└── .github/
```

Rules for monorepos:

- **Apps consume packages. Packages never import apps.**
- Each package has its own README, even if it is three lines.
- Shared tooling lives in one place (`packages/config` or the root). Do not copy `tsconfig` twelve times.
- Boundaries are real: public exports, versioned if you publish, and no reaching into another package's `src/internal`.
- CI should run only what changed. If every package builds on every commit, the workspace is decorative.

If you have two apps that do not share code, you do not have a monorepo problem. You have two repos.

## Tests

### Colocate unit tests with the code they prove

```text
src/features/billing/pricing.ts
src/features/billing/pricing.test.ts
```

A test that is six folders away from its subject will not be updated.

### Keep end-to-end tests separate

E2E tests are a different artefact: slower, broader, and usually run in CI against a running app.

```text
tests/
├── e2e/
├── integration/          # optional, for multi-module tests that are not E2E
└── fixtures/
```

or, if the toolchain prefers it:

```text
e2e/
fixtures/
```

### Fixtures and snapshots

- Golden files and fixtures live next to the tests that use them, or in a clearly named `fixtures/` folder.
- Snapshots are generated output. Review them. Do not let them sprawl into source folders.

### Do not ship tests in production bundles

Keep test files out of published packages. Name them so tooling can exclude them (`*.test.ts`, `*_test.go`, `test_*.py`).

## Documentation

Docs have a job: they survive the people who wrote the code.

```text
docs/
├── README.md             # index of the docs themselves
├── architecture.md
├── decisions/            # Architecture Decision Records
│   ├── 0001-record-architecture-decisions.md
│   └── 0002-use-feature-folders.md
├── runbooks/
└── contributing.md       # if CONTRIBUTING.md at root is only a pointer
```

### What goes where

| Kind | Home |
| --- | --- |
| How to run the project | `README.md` |
| How to contribute | `CONTRIBUTING.md` |
| Why we chose X over Y | `docs/decisions/` (ADRs) |
| How the system fits together | `docs/architecture.md` |
| What to do at 2am when it breaks | `docs/runbooks/` |
| Public API for a library | next to the code, generated if you can |
| Comments in code | *why*, never *what* |

### ADRs

When you make a decision that is expensive to reverse, write a short record:

- Title and date
- Context
- Decision
- Consequences

Number them. Do not rewrite history; add a superseding record.

### Comments

If a comment restates the next line, delete it. If it explains a constraint, a workaround, or a trade-off the code cannot express, keep it — and consider promoting it to an ADR.

## Configuration and tooling

### Tool config lives at the root

One `eslint.config.js`, one `tsconfig.json`, one `pyproject.toml`. Apps in a monorepo extend the root; they do not fork it.

Do not scatter `.rc` files without a reason. Prefer the ecosystem's current standard (for example `eslint.config.js` over `.eslintrc`).

### App config is not tool config

Runtime configuration — feature flags, service URLs, limits — belongs in a dedicated module (`src/shared/config/`, `config/`) with a schema and defaults. It does not belong in random constants files.

### Environment

- Commit `.env.example` (or `.env.sample`) with dummy values and comments.
- Never commit `.env`, `.env.local`, or credential files.
- Document every variable: name, purpose, default, and whether it is required.
- Prefer a small set of variables. If you need forty, you probably need a config file with a schema instead.

### Editor and local tooling

A shared `.editorconfig` is enough for most teams. Commit `.vscode/settings.json` or `.vscode/extensions.json` only when the team has agreed the repo should set those defaults. Never commit personal `launch.json` secrets or local history.

## Scripts and CI

### `scripts/` is for humans and machines

```text
scripts/
├── bootstrap.sh          # install, generate, migrate — one entry for new machines
├── check.sh              # the same checks CI runs
└── release.sh
```

Rules:

- The README's commands call these scripts (or package-manager scripts that wrap them).
- CI calls the same scripts. Do not maintain two pipelines that drift.
- Scripts are boring: `set -euo pipefail`, no hidden network, no undeclared prerequisites.
- If a script is three lines of `npm`, it can live in `package.json`. If it has branches, it belongs in `scripts/`.

### Package scripts as the public interface

For a Node app, a useful minimum:

```text
dev       # run locally
build     # production artefact
test      # unit / integration
lint      # static checks
typecheck # if the linter does not already
check     # lint + types + tests, what CI and pre-push run
```

Name them plainly. `npm run start:local:debug:v2` is not an interface.

### CI

```text
.github/
├── workflows/
│   ├── ci.yml            # pull requests: check, build
│   └── release.yml       # tags or main: publish / deploy
├── CODEOWNERS
├── PULL_REQUEST_TEMPLATE.md
└── ISSUE_TEMPLATE/
```

Keep workflows short. They should install, then call `scripts/check.sh` (or `npm run check`). Logic that exists only in YAML will not be run on laptops, so it will rot.

### CODEOWNERS

Map folders to teams. Feature folders make this honest:

```text
/src/features/billing/    @acme/billing
/src/features/auth/       @acme/identity
/docs/                    @acme/docs
```

If you cannot write CODEOWNERS without lying, the folder structure does not match how the organisation works. Fix the folders.

## Assets

- **`public/`** (or `static/`): files served as-is — `favicon.svg`, `robots.txt`, images referenced by URL. Paths are stable.
- **`src/assets/`**: files the bundler should hash, inline, or transform.
- **`docs/assets/`**: images that belong to documentation, not the product.

Do not drop a designer's unfiltered export folder into `src/`. Curate. Name files after their role (`logo-mark.svg`, not `Final_v7_REALLY_final.png`).

Generated media belongs in `dist/` or a CDN, not in git, unless it is a small, versioned artefact the app cannot build without.

## Generated output and third-party code

Git is for source. The following are not source:

| Artefact | Action |
| --- | --- |
| `node_modules/`, virtualenvs, `target/` | gitignore |
| `dist/`, `build/`, `.next/`, `coverage/` | gitignore |
| Lockfiles | **commit** for applications; libraries may differ |
| Generated API clients | commit only if generation is awkward for contributors; otherwise generate in `bootstrap` |
| Vendored third-party code | avoid; if you must, isolate under `vendor/` and document why |

If a generated file is committed, the command that regenerates it is documented, and CI fails when the committed copy is stale.

## Git hygiene

Organisation is also history.

- **Small, focused commits** that explain *why*. The folder you touched should match the commit's subject.
- **Branch names** that map to work (`feat/billing-proration`, `fix/auth-session-expiry`), not `johns-wip-2`.
- **`main` is sacred.** No force-push, no direct commits if you use pull requests.
- **Do not commit secrets, and do not rewrite public history to hide them.** Rotate the secret; treat the old one as public.
- **Keep `main` releasable.** Broken trees on the default branch make every other rule harder to trust.

A useful `.gitignore` baseline:

```text
# dependencies and virtualenvs
node_modules/
.venv/
vendor/bundle/

# build and test output
dist/
build/
coverage/
*.tsbuildinfo

# environment and secrets
.env
.env.*
!.env.example

# editor and OS
.DS_Store
Thumbs.db
*.swp
.idea/
.vscode/*
!.vscode/extensions.json
!.vscode/settings.json
```

Adjust to the stack. Review it when you add a tool.

## Naming

Consistent names are a layout rule.

| Thing | Convention |
| --- | --- |
| Repositories | short, lowercase, hyphenated: `billing-api`, not `Billing_API_NEW` |
| Folders | same as repos; no spaces, no camelCase in paths |
| JS/TS files | kebab-case, named after the main export |
| Python modules | snake_case |
| Go files | snake_case, package name is the parent folder |
| Tests | `<name>.test.ts`, `<name>_test.go`, `test_<name>.py` |
| Docs | kebab-case, descriptive: `auth-session-model.md` |
| Env vars | `SCREAMING_SNAKE_CASE`, prefixed by the app if they might collide |

Never use `new`, `old`, `temp`, `final`, `wip`, or a date as a folder name. Those names are how graveyards start.

## What not to do

These patterns show up constantly. They are all avoidable.

- **Type-first folders** (`components/`, `hooks/`, `utils/`) as the primary split.
- **`misc/`, `common/`, `helpers/`, `shared/utils.ts`** as a destination for thought.
- **`old/`, `backup/`, `v2/`** sitting next to the live code. Use git history.
- **Several competing READMEs** at the root (`README.md`, `README.new.md`, `docs.md`).
- **Copy-pasted config** in every package of a monorepo.
- **Checking in `node_modules` or build output** "so it works offline". Use the lockfile and a cache.
- **Deep inheritance of folders** that mirrors an org chart from three restructures ago.
- **Mixing apps at the root** (`frontend/`, `backend/`, `mobile/` with no workspace tooling). That is a monorepo that has not admitted it yet — or three repos in a trench coat.
- **Putting secrets in example files** "because they are only dev keys". Dev keys leak too.
- **Renaming folders in giant cosmetic PRs.** Layout changes should ship with the feature that needs them, or as a dedicated move with a working tree at every commit.

## A worked example

A mid-size product, one app, a few libraries, a real team:

```text
.
├── README.md
├── LICENSE
├── CHANGELOG.md
├── CONTRIBUTING.md
├── SECURITY.md
├── package.json
├── pnpm-lock.yaml
├── pnpm-workspace.yaml
├── tsconfig.base.json
├── .gitignore
├── .editorconfig
├── .nvmrc
├── .env.example
├── apps/
│   └── web/
│       ├── package.json
│       ├── README.md
│       ├── src/
│       │   ├── routes/
│       │   ├── features/
│       │   │   ├── auth/
│       │   │   ├── billing/
│       │   │   └── catalog/
│       │   └── shared/
│       ├── public/
│       └── tests/e2e/
├── packages/
│   ├── ui/
│   ├── api-client/
│   └── config/
├── docs/
│   ├── architecture.md
│   ├── decisions/
│   └── runbooks/
├── scripts/
│   ├── bootstrap.sh
│   └── check.sh
└── .github/
    ├── CODEOWNERS
    └── workflows/
        └── ci.yml
```

A new engineer should be able to narrate this tree out loud. If they cannot, keep simplifying.

## Adopting this in an existing repo

Do not boil the ocean. Layout is a garden you tidy in passing.

### Adoption checklist

- [ ] Write or rewrite the README so a stranger can run the project
- [ ] Add a licence, `.gitignore`, and `.env.example`
- [ ] Move generated output out of git if it slipped in
- [ ] Collapse the root: only lobby files remain
- [ ] Introduce `src/` (or the language's equivalent) if application code is loose at the root
- [ ] Pick feature folders for the next new work; stop adding to type folders
- [ ] Colocate new tests with their subject
- [ ] Put automation in `scripts/` and make CI call it
- [ ] Add `docs/decisions/` and record the layout choice itself as ADR 0001
- [ ] Add CODEOWNERS that match the folders
- [ ] Delete `old/`, `misc/`, and duplicate READMEs
- [ ] Ban new files at the root in review, except when the lobby actually needs them

Move code when you touch it. A six-month migration that only moves files will stall; a rule that "new work follows the standard" will not.

## Review questions

Use these in code review. If the answer is no, request a move before merge.

1. Could a new contributor guess this file's folder from its name?
2. Does this change sit inside one feature, or did it scatter?
3. Did we add a file to the root that is not a lobby file?
4. Did we add a helper to `shared/` that only one feature uses?
5. Is anything generated being committed without a regenerate command?
6. Would CODEOWNERS ping the right team?

## Further reading

These are the documents this standard is in conversation with. Steal from them freely.

- [A Philosophy of Software Design](https://web.stanford.edu/~ouster/cgi-bin/book.php) — modules as secrets, not as file types
- [Architecture Decision Records](https://adr.github.io/) — how to write the exceptions
- [Keep a Changelog](https://keepachangelog.com/) — what belongs in `CHANGELOG.md`
- [Conventional Commits](https://www.conventionalcommits.org/) — how history should read
- [The Twelve-Factor App](https://12factor.net/) — config, build, and release as separate concerns
- [Go project layout](https://go.dev/doc/modules/layout) — language-idiomatic trees beat generic ones
- [Semantic Versioning](https://semver.org/) — when a folder is also a published package
