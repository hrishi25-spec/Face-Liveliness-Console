import { i as __toESM } from "../_runtime.mjs";
import { n as require_react } from "../_libs/@radix-ui/react-compose-refs+[...].mjs";
import { b as require_jsx_runtime } from "../_libs/@tanstack/react-router+[...].mjs";
import { a as FolderTree, c as Check, i as Menu, o as Download, r as Search, s as Copy, t as X } from "../_libs/lucide-react.mjs";
import { t as Markdown } from "../_libs/react-markdown+[...].mjs";
import { t as remarkGfm } from "../_libs/remark-gfm.mjs";
import { n as clsx, t as cva } from "../_libs/class-variance-authority+clsx.mjs";
import { t as Slot } from "../_libs/radix-ui__react-slot.mjs";
import { t as twMerge } from "../_libs/tailwind-merge.mjs";
//#region node_modules/.nitro/vite/services/ssr/assets/routes-Bz0JCc5s.js
var import_react = /* @__PURE__ */ __toESM(require_react());
var import_jsx_runtime = require_jsx_runtime();
function cn(...inputs) {
	return twMerge(clsx(inputs));
}
var buttonVariants = cva("inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-md text-sm font-medium transition-[opacity,background-color,color,box-shadow,border-color] duration-150 ease-out focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40 disabled:pointer-events-none disabled:opacity-50", {
	variants: {
		variant: {
			primary: "bg-primary text-primary-fg hover:opacity-90",
			outline: "border border-border bg-surface text-fg hover:bg-code",
			ghost: "text-fg hover:bg-code"
		},
		size: {
			default: "min-h-11 px-4",
			sm: "min-h-11 px-3",
			icon: "size-11 px-0"
		}
	},
	defaultVariants: {
		variant: "primary",
		size: "default"
	}
});
function Button({ className, variant, size, asChild = false, ...props }) {
	return /* @__PURE__ */ (0, import_jsx_runtime.jsx)(asChild ? Slot : "button", {
		className: cn(buttonVariants({
			variant,
			size
		}), className),
		...props
	});
}
function slugify(value) {
	return value.toLowerCase().replace(/&/g, " and ").replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");
}
function parseToc(markdown) {
	const items = [];
	for (const line of markdown.split("\n")) {
		const match = /^(#{2,3})\s+(.+)$/.exec(line);
		if (!match) continue;
		const level = match[1].length;
		const text = match[2].replace(/[*_`[\]]/g, "").trim();
		items.push({
			level,
			text,
			id: slugify(text)
		});
	}
	return items;
}
function splitGuide(markdown) {
	const match = /^#\s+(.+)\n+/.exec(markdown);
	if (!match) return {
		title: "How a repository should be organised",
		body: markdown
	};
	return {
		title: match[1].trim(),
		body: markdown.slice(match[0].length)
	};
}
function flattenText(node) {
	if (node == null || typeof node === "boolean") return "";
	if (typeof node === "string" || typeof node === "number") return String(node);
	if (Array.isArray(node)) return node.map(flattenText).join("");
	if (typeof node === "object" && node !== null && "props" in node) {
		const props = node.props;
		return flattenText(props?.children);
	}
	return "";
}
function downloadText(filename, content, mime) {
	const blob = new Blob([content], { type: mime });
	const url = URL.createObjectURL(blob);
	const anchor = document.createElement("a");
	anchor.href = url;
	anchor.download = filename;
	document.body.appendChild(anchor);
	anchor.click();
	anchor.remove();
	URL.revokeObjectURL(url);
}
var CHECKLIST_KEY = "repo-order-checklist";
function GuideReader({ source }) {
	const { title, body } = (0, import_react.useMemo)(() => splitGuide(source), [source]);
	const toc = (0, import_react.useMemo)(() => parseToc(source), [source]);
	const [query, setQuery] = (0, import_react.useState)("");
	const [activeId, setActiveId] = (0, import_react.useState)(toc[0]?.id ?? "");
	const [menuOpen, setMenuOpen] = (0, import_react.useState)(false);
	const [copied, setCopied] = (0, import_react.useState)(false);
	const [checked, setChecked] = (0, import_react.useState)({});
	(0, import_react.useEffect)(() => {
		try {
			const raw = localStorage.getItem(CHECKLIST_KEY);
			if (raw) setChecked(JSON.parse(raw));
		} catch {}
	}, []);
	(0, import_react.useEffect)(() => {
		const headings = Array.from(document.querySelectorAll(".guide-prose h2, .guide-prose h3"));
		if (headings.length === 0) return;
		const observer = new IntersectionObserver((entries) => {
			const id = entries.filter((entry) => entry.isIntersecting).sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top)[0]?.target.id;
			if (id) setActiveId(id);
		}, {
			rootMargin: "-20% 0px -65% 0px",
			threshold: [0, 1]
		});
		headings.forEach((heading) => observer.observe(heading));
		return () => observer.disconnect();
	}, [body]);
	(0, import_react.useEffect)(() => {
		function onKey(event) {
			if (event.key === "Escape") setMenuOpen(false);
			if (event.key === "/" && !(event.target instanceof HTMLInputElement)) {
				event.preventDefault();
				document.getElementById("guide-search")?.focus();
			}
		}
		window.addEventListener("keydown", onKey);
		return () => window.removeEventListener("keydown", onKey);
	}, []);
	const filteredToc = (0, import_react.useMemo)(() => {
		const needle = query.trim().toLowerCase();
		if (!needle) return toc;
		return toc.filter((item) => item.text.toLowerCase().includes(needle));
	}, [query, toc]);
	function toggleCheck(id) {
		setChecked((prev) => {
			const next = {
				...prev,
				[id]: !prev[id]
			};
			localStorage.setItem(CHECKLIST_KEY, JSON.stringify(next));
			return next;
		});
	}
	async function copyMarkdown() {
		try {
			await navigator.clipboard.writeText(source);
		} catch {
			const area = document.createElement("textarea");
			area.value = source;
			area.setAttribute("readonly", "");
			area.style.position = "fixed";
			area.style.left = "-9999px";
			document.body.appendChild(area);
			area.select();
			document.execCommand("copy");
			area.remove();
		}
		setCopied(true);
		window.setTimeout(() => setCopied(false), 1600);
	}
	function downloadMarkdown() {
		downloadText("REPO_ORGANIZATION.md", source, "text/markdown;charset=utf-8");
	}
	return /* @__PURE__ */ (0, import_jsx_runtime.jsxs)("div", {
		className: "min-h-dvh bg-bg text-fg",
		children: [/* @__PURE__ */ (0, import_jsx_runtime.jsx)("header", {
			className: "no-print sticky top-0 z-30 border-b border-border bg-bg/90 backdrop-blur-sm",
			children: /* @__PURE__ */ (0, import_jsx_runtime.jsxs)("div", {
				className: "mx-auto flex h-14 max-w-6xl items-center gap-3 px-4 sm:px-6",
				children: [
					/* @__PURE__ */ (0, import_jsx_runtime.jsx)(Button, {
						variant: "ghost",
						size: "icon",
						className: "lg:hidden",
						"aria-label": "Open contents",
						onClick: () => setMenuOpen(true),
						children: /* @__PURE__ */ (0, import_jsx_runtime.jsx)(Menu, { className: "size-5" })
					}),
					/* @__PURE__ */ (0, import_jsx_runtime.jsxs)("a", {
						href: "#top",
						className: "flex min-w-0 items-center gap-2.5",
						children: [/* @__PURE__ */ (0, import_jsx_runtime.jsx)("span", {
							className: "flex size-8 items-center justify-center rounded-md bg-primary text-primary-fg",
							children: /* @__PURE__ */ (0, import_jsx_runtime.jsx)(FolderTree, {
								className: "size-4",
								strokeWidth: 1.75
							})
						}), /* @__PURE__ */ (0, import_jsx_runtime.jsxs)("span", {
							className: "min-w-0",
							children: [/* @__PURE__ */ (0, import_jsx_runtime.jsx)("span", {
								className: "block truncate text-sm font-semibold tracking-tight",
								children: "Repo Order"
							}), /* @__PURE__ */ (0, import_jsx_runtime.jsx)("span", {
								className: "hidden text-xs text-muted sm:block",
								children: "A handbook for repository layout"
							})]
						})]
					}),
					/* @__PURE__ */ (0, import_jsx_runtime.jsxs)("div", {
						className: "ml-auto flex items-center gap-2",
						children: [/* @__PURE__ */ (0, import_jsx_runtime.jsxs)(Button, {
							variant: "ghost",
							onClick: () => void copyMarkdown(),
							children: [copied ? /* @__PURE__ */ (0, import_jsx_runtime.jsx)(Check, { className: "size-4" }) : /* @__PURE__ */ (0, import_jsx_runtime.jsx)(Copy, { className: "size-4" }), /* @__PURE__ */ (0, import_jsx_runtime.jsx)("span", {
								className: "hidden sm:inline",
								children: copied ? "Copied" : "Copy"
							})]
						}), /* @__PURE__ */ (0, import_jsx_runtime.jsxs)(Button, {
							onClick: downloadMarkdown,
							children: [
								/* @__PURE__ */ (0, import_jsx_runtime.jsx)(Download, { className: "size-4" }),
								/* @__PURE__ */ (0, import_jsx_runtime.jsx)("span", {
									className: "hidden sm:inline",
									children: "Download .md"
								}),
								/* @__PURE__ */ (0, import_jsx_runtime.jsx)("span", {
									className: "sm:hidden",
									children: ".md"
								})
							]
						})]
					})
				]
			})
		}), /* @__PURE__ */ (0, import_jsx_runtime.jsxs)("div", {
			className: "mx-auto flex max-w-6xl",
			children: [
				/* @__PURE__ */ (0, import_jsx_runtime.jsx)("aside", {
					className: "no-print hidden w-64 shrink-0 border-r border-border lg:block",
					children: /* @__PURE__ */ (0, import_jsx_runtime.jsx)("div", {
						className: "toc-pane sticky top-14 overflow-y-auto px-4 py-6",
						children: /* @__PURE__ */ (0, import_jsx_runtime.jsx)(TocPanel, {
							query,
							onQuery: setQuery,
							items: filteredToc,
							activeId,
							onJump: () => void 0
						})
					})
				}),
				menuOpen ? /* @__PURE__ */ (0, import_jsx_runtime.jsxs)("div", {
					className: "no-print fixed inset-0 z-40 lg:hidden",
					children: [/* @__PURE__ */ (0, import_jsx_runtime.jsx)("button", {
						type: "button",
						className: "absolute inset-0 bg-fg/30",
						"aria-label": "Close contents",
						onClick: () => setMenuOpen(false)
					}), /* @__PURE__ */ (0, import_jsx_runtime.jsxs)("aside", {
						className: "absolute inset-y-0 left-0 flex w-[min(20rem,88vw)] flex-col bg-surface shadow-border",
						children: [/* @__PURE__ */ (0, import_jsx_runtime.jsxs)("div", {
							className: "flex h-14 items-center justify-between border-b border-border px-3",
							children: [/* @__PURE__ */ (0, import_jsx_runtime.jsx)("p", {
								className: "px-2 text-sm font-semibold",
								children: "Contents"
							}), /* @__PURE__ */ (0, import_jsx_runtime.jsx)(Button, {
								variant: "ghost",
								size: "icon",
								"aria-label": "Close contents",
								onClick: () => setMenuOpen(false),
								children: /* @__PURE__ */ (0, import_jsx_runtime.jsx)(X, { className: "size-5" })
							})]
						}), /* @__PURE__ */ (0, import_jsx_runtime.jsx)("div", {
							className: "flex-1 overflow-y-auto px-4 py-5",
							children: /* @__PURE__ */ (0, import_jsx_runtime.jsx)(TocPanel, {
								query,
								onQuery: setQuery,
								items: filteredToc,
								activeId,
								onJump: () => setMenuOpen(false)
							})
						})]
					})]
				}) : null,
				/* @__PURE__ */ (0, import_jsx_runtime.jsxs)("main", {
					id: "top",
					className: "min-w-0 flex-1 overflow-x-hidden px-5 py-10 sm:px-8 lg:px-12 lg:py-14",
					children: [
						/* @__PURE__ */ (0, import_jsx_runtime.jsx)("p", {
							className: "text-xs font-medium tracking-widest text-muted uppercase",
							children: "Engineering standard"
						}),
						/* @__PURE__ */ (0, import_jsx_runtime.jsx)("h1", {
							className: "font-display mt-3 max-w-prose text-4xl font-medium leading-tight tracking-tight sm:text-5xl",
							children: title
						}),
						/* @__PURE__ */ (0, import_jsx_runtime.jsx)("p", {
							className: "mt-5 max-w-prose text-lg leading-relaxed text-muted",
							children: "Opinionated layout rules for source, tests, docs, scripts, and the files that belong at the root — written so a stranger can navigate the tree in five minutes."
						}),
						/* @__PURE__ */ (0, import_jsx_runtime.jsx)("article", {
							className: "guide-prose mt-10 w-full min-w-0 max-w-prose",
							children: /* @__PURE__ */ (0, import_jsx_runtime.jsx)(Markdown, {
								remarkPlugins: [remarkGfm],
								components: {
									h1: () => null,
									h2: ({ children }) => /* @__PURE__ */ (0, import_jsx_runtime.jsx)("h2", {
										id: slugify(flattenText(children)),
										children
									}),
									h3: ({ children }) => /* @__PURE__ */ (0, import_jsx_runtime.jsx)("h3", {
										id: slugify(flattenText(children)),
										children
									}),
									pre: ({ children }) => /* @__PURE__ */ (0, import_jsx_runtime.jsx)(CodeBlock, { children }),
									table: ({ children }) => /* @__PURE__ */ (0, import_jsx_runtime.jsx)("div", {
										className: "my-5 overflow-x-auto",
										children: /* @__PURE__ */ (0, import_jsx_runtime.jsx)("table", { children })
									}),
									a: ({ href, children }) => /* @__PURE__ */ (0, import_jsx_runtime.jsx)("a", {
										href,
										target: href?.startsWith("http") ? "_blank" : void 0,
										rel: href?.startsWith("http") ? "noreferrer" : void 0,
										children
									}),
									li: ({ children, className, ...props }) => {
										if (!className?.includes("task-list-item")) return /* @__PURE__ */ (0, import_jsx_runtime.jsx)("li", {
											className,
											...props,
											children
										});
										const id = slugify(flattenText(children));
										return /* @__PURE__ */ (0, import_jsx_runtime.jsx)("li", {
											className: cn(className, "task-item"),
											...props,
											children: /* @__PURE__ */ (0, import_jsx_runtime.jsxs)("label", {
												className: "flex cursor-pointer items-start gap-3",
												children: [/* @__PURE__ */ (0, import_jsx_runtime.jsx)("input", {
													type: "checkbox",
													className: "mt-1 size-4 shrink-0 accent-primary",
													checked: Boolean(checked[id]),
													onChange: () => toggleCheck(id)
												}), /* @__PURE__ */ (0, import_jsx_runtime.jsx)("span", {
													className: checked[id] ? "text-muted line-through" : void 0,
													children: stripLeadingCheckbox(children)
												})]
											})
										});
									}
								},
								children: body
							})
						})
					]
				})
			]
		})]
	});
}
function TocPanel({ query, onQuery, items, activeId, onJump }) {
	return /* @__PURE__ */ (0, import_jsx_runtime.jsxs)("div", { children: [
		/* @__PURE__ */ (0, import_jsx_runtime.jsxs)("label", {
			className: "relative block",
			children: [/* @__PURE__ */ (0, import_jsx_runtime.jsx)(Search, { className: "pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-subtle" }), /* @__PURE__ */ (0, import_jsx_runtime.jsx)("input", {
				id: "guide-search",
				type: "search",
				value: query,
				onChange: (event) => onQuery(event.target.value),
				placeholder: "Search sections",
				className: "h-11 w-full rounded-md border border-border bg-surface pr-3 pl-10 text-sm text-fg outline-none ring-primary/30 placeholder:text-subtle focus:ring-2"
			})]
		}),
		/* @__PURE__ */ (0, import_jsx_runtime.jsx)("p", {
			className: "mt-6 mb-2 px-1 text-xs font-semibold tracking-widest text-muted uppercase",
			children: "Contents"
		}),
		items.length === 0 ? /* @__PURE__ */ (0, import_jsx_runtime.jsx)("p", {
			className: "px-1 text-sm text-muted",
			children: "No sections match."
		}) : /* @__PURE__ */ (0, import_jsx_runtime.jsx)("nav", {
			"aria-label": "Table of contents",
			children: /* @__PURE__ */ (0, import_jsx_runtime.jsx)("ul", {
				className: "flex flex-col gap-0.5",
				children: items.map((item) => /* @__PURE__ */ (0, import_jsx_runtime.jsx)("li", { children: /* @__PURE__ */ (0, import_jsx_runtime.jsx)("a", {
					href: `#${item.id}`,
					onClick: onJump,
					className: cn("block rounded-md py-1.5 text-sm leading-snug transition-colors duration-150", item.level === 3 ? "px-1 pl-4" : "px-1 font-medium", activeId === item.id ? "bg-code text-fg" : "text-muted hover:bg-code/70 hover:text-fg"),
					children: item.text
				}) }, item.id))
			})
		})
	] });
}
function CodeBlock({ children }) {
	const [copied, setCopied] = (0, import_react.useState)(false);
	const text = flattenText(children).replace(/\n$/, "");
	return /* @__PURE__ */ (0, import_jsx_runtime.jsxs)("div", {
		className: "group relative my-5 w-full min-w-0 overflow-x-auto rounded-lg bg-code",
		children: [/* @__PURE__ */ (0, import_jsx_runtime.jsx)("button", {
			type: "button",
			className: "absolute top-2 right-2 inline-flex size-9 items-center justify-center rounded-md text-muted opacity-100 transition-opacity duration-150 hover:bg-rail hover:text-fg sm:opacity-0 sm:group-hover:opacity-100",
			"aria-label": copied ? "Copied" : "Copy code",
			onClick: () => {
				navigator.clipboard.writeText(text);
				setCopied(true);
				window.setTimeout(() => setCopied(false), 1500);
			},
			children: copied ? /* @__PURE__ */ (0, import_jsx_runtime.jsx)(Check, { className: "size-4" }) : /* @__PURE__ */ (0, import_jsx_runtime.jsx)(Copy, { className: "size-4" })
		}), /* @__PURE__ */ (0, import_jsx_runtime.jsx)("pre", {
			className: "min-w-0 overflow-x-auto p-4 font-mono text-fg",
			children
		})]
	});
}
function stripLeadingCheckbox(children) {
	if (!Array.isArray(children)) return children;
	return children.filter((child) => {
		if (typeof child !== "object" || child == null || !("props" in child)) return true;
		return child.props?.type !== "checkbox";
	});
}
var repo_organization_default = "# How a repository should be organised\n\nA repository is a map, not a dump. Someone who has never seen the project should be able to clone it, understand what it is, run it, and find the code they need — in minutes, not an afternoon.\n\nThis guide is a practical standard. Follow it unless you have a written reason not to.\n\n## The standard in one page\n\n1. **The root is a lobby.** Only files a new contributor needs in the first ten minutes live there.\n2. **One place for each kind of thing.** Source, tests, docs, scripts, assets, and generated output never share a folder.\n3. **Name for the reader.** Folders describe *what the software does*, not *what kind of file it is*.\n4. **Colocate what changes together.** A feature's UI, logic, and tests sit next to each other.\n5. **Generated files are not source.** Build output, coverage, caches, and vendor installs are gitignored.\n6. **Secrets never enter git.** Not even once. Not even encrypted in a pinch.\n7. **The README is the front door.** It answers what, why, how to run, how to test, and where to go next.\n8. **Scripts are the interface to automation.** Humans and CI call the same named commands.\n9. **Depth is a smell.** More than three or four folder levels usually means the split is wrong.\n10. **Document exceptions.** If the layout diverges, say so in the README. Do not leave people guessing.\n\nIf you do only these ten, the rest of this document is commentary.\n\n## Why organisation matters\n\nA messy repo taxes every future change:\n\n- Onboarding stretches from an hour to a week.\n- Reviews stall because reviewers cannot find the seam.\n- CI becomes a pile of special cases.\n- Ownership blurs — and unowned code rots.\n\nOrganisation is not aesthetics. It is how a team scales without a tour guide.\n\n## Principles\n\n### One obvious place\n\nIf two reasonable people would put the same file in two different folders, the scheme has failed. Pick a rule, write it down, apply it everywhere.\n\n### The root is a lobby, not a junk drawer\n\nA stranger's first view of the repo is `ls` at the root. That listing should read like a table of contents, not a desktop.\n\n### Name for the reader, not the author\n\n`billing/`, `auth/`, and `catalog/` tell a story. `helpers/`, `common/`, `misc/`, `new/`, and `stuff/` do not.\n\n### Colocate change\n\nIf a bugfix always touches three folders, those three folders should have been one.\n\n### Hide the machine's mess\n\n`dist/`, `build/`, `.next/`, `coverage/`, `.turbo/`, `node_modules/`, `__pycache__/`, and `target/` are artefacts. They belong in `.gitignore`, not in history.\n\n### Make the happy path obvious\n\nThe default way to install, run, test, lint, and ship should be three commands or fewer, documented at the top of the README.\n\n## The root\n\nKeep the root small and stable. A healthy root looks like this:\n\n```text\n.\n├── README.md\n├── LICENSE\n├── CHANGELOG.md\n├── CONTRIBUTING.md\n├── CODE_OF_CONDUCT.md\n├── SECURITY.md\n├── package.json          # or pyproject.toml / go.mod / Cargo.toml\n├── package-lock.json     # the lockfile for your package manager\n├── tsconfig.json         # language/tool config lives at the root\n├── .gitignore\n├── .editorconfig\n├── .nvmrc                # or .python-version / .tool-versions\n├── Dockerfile            # only if you ship a container\n├── src/\n├── tests/                # only if tests are not colocated\n├── docs/\n├── scripts/\n├── public/               # static assets served as-is\n└── .github/\n```\n\n### What belongs at the root\n\n| File | Purpose |\n| --- | --- |\n| `README.md` | What this is, why it exists, how to run it |\n| `LICENSE` | The licence. Unlicensed work is unusable. |\n| `CHANGELOG.md` | User-facing changes, newest first |\n| `CONTRIBUTING.md` | How to propose work, review, and release |\n| `CODE_OF_CONDUCT.md` | How people treat each other |\n| `SECURITY.md` | How to report a vulnerability |\n| `package.json` / `pyproject.toml` / `go.mod` / `Cargo.toml` | The project manifest |\n| Lockfile | Reproducible installs. Always commit it for apps. |\n| Tool config | `tsconfig.json`, `eslint.config.js`, `vitest.config.ts`, and similar |\n| `.gitignore` | What git must never see |\n| `.editorconfig` | Cross-editor basics: charset, indentation, newlines |\n| Runtime pin | `.nvmrc`, `.python-version`, or `.tool-versions` |\n| `Dockerfile` / `compose.yaml` | Only if this repo actually ships or develops in containers |\n\n### What does not belong at the root\n\n- Scratch notes, meeting dumps, and personal TODO files\n- Zipped backups and `old/` folders\n- Editor folders except a shared `.vscode/` or `.idea/` the team has agreed to\n- Environment files with real secrets (`.env`, `.env.local`, credentials JSON)\n- Build output\n- One-off scripts that are not part of the project's interface\n- Screenshots of the app taken during development\n\nIf a file is useful, give it a real home (`docs/`, `scripts/`, `assets/`). If it is not useful, delete it.\n\n### README as the front door\n\nA README that earns its place answers, in this order:\n\n1. **Name and one-sentence purpose.**\n2. **Status.** Stable, experimental, deprecated — plus a link if something replaces it.\n3. **Requirements.** Language version, system packages, services.\n4. **Quick start.** Clone, install, configure, run. Copy-pasteable.\n5. **Tests and quality.** The one command that must pass before a review.\n6. **Project map.** A short pointer to `src/`, `docs/`, `scripts/`.\n7. **How to contribute.** Link `CONTRIBUTING.md`; do not duplicate it.\n8. **Licence and contact.**\n\nDo not put architecture essays in the README. Link `docs/` instead.\n\n## Source code\n\n### Prefer a `src/` (or language equivalent)\n\nA dedicated source root keeps tooling simple and stops application code from mixing with config.\n\n| Language | Conventional source root |\n| --- | --- |\n| TypeScript / JavaScript | `src/` |\n| Python | package directory named after the project, not a grab-bag `src` of scripts |\n| Go | module root; packages as subfolders. No `src/` — that is not idiomatic Go |\n| Rust | `src/` with `lib.rs` or `main.rs` |\n| Java / Kotlin | `src/main/java` (or `kotlin`) as the build expects |\n| Ruby | `lib/` and `app/` (Rails) |\n\nFollow the language. Do not invent a clever layout the ecosystem's tools will fight.\n\n### Feature folders, not type folders\n\nOrganise around *capabilities*, not file kinds.\n\nAvoid this:\n\n```text\nsrc/\n├── components/\n├── hooks/\n├── utils/\n├── types/\n├── services/\n└── store/\n```\n\nThis layout answers \"what is a React component?\" It does not answer \"where does checkout live?\" Every feature is scattered across six folders, and a change to billing means a scavenger hunt.\n\nPrefer this:\n\n```text\nsrc/\n├── app/                  # shell: routing, providers, global styles\n├── features/\n│   ├── billing/\n│   │   ├── invoice-list.tsx\n│   │   ├── invoice-list.test.ts\n│   │   ├── pricing.ts\n│   │   └── index.ts      # the public API of this feature\n│   ├── auth/\n│   └── catalog/\n├── shared/               # truly cross-cutting primitives only\n│   ├── ui/\n│   ├── lib/\n│   └── types/\n└── styles/\n```\n\nRules for `shared/`:\n\n- If it is used by one feature, it is not shared. Move it back.\n- If it is a grab-bag, it will become a junk drawer. Split by purpose (`ui/`, `lib/date/`, `lib/http/`).\n- A file named `helpers.ts` or `utils.ts` at any depth is a warning.\n\n### Public API of a folder\n\nA feature folder should have a narrow front door — usually `index.ts` (or `__init__.py`, or the package's public files).\n\n- Other features import from the folder, not from files inside it.\n- Internal files can move without breaking the rest of the tree.\n- Do not re-export everything. A barrel that dumps 40 names is not a boundary.\n\n### Depth\n\nIf you need a path like `src/features/billing/invoices/list/components/row/cells/`, the design is wrong. Flatten. Three levels under `src/` is comfortable; four is the usual limit; five wants a reason.\n\n### Files, not trivia\n\n- One main idea per file. Not one function per file, and not a 2,000-line \"god file\".\n- Name files after the thing they export: `invoice-list.tsx` exports `InvoiceList`.\n- Avoid `index.ts` as a hiding place for real logic. Index files re-export; they do not implement.\n- Prefer kebab-case for files in JS/TS (`invoice-list.tsx`). Match the language's convention elsewhere (`invoice_list.py`, `invoice_list.go`).\n\n## Language-shaped trees\n\nThe same principles, applied to common stacks. Copy the one you need.\n\n### TypeScript application\n\n```text\n.\n├── src/\n│   ├── routes/           # or app/, pages/ — match the framework\n│   ├── features/\n│   ├── shared/\n│   └── styles/\n├── public/\n├── tests/e2e/            # end-to-end only; unit tests sit next to source\n├── scripts/\n├── docs/\n├── package.json\n├── tsconfig.json\n└── vite.config.ts\n```\n\n### Python package\n\n```text\n.\n├── src/\n│   └── billing_service/\n│       ├── __init__.py\n│       ├── invoices.py\n│       └── payments/\n├── tests/\n├── docs/\n├── scripts/\n├── pyproject.toml\n├── README.md\n└── .python-version\n```\n\nKeep the import name (`billing_service`) identical to the distribution name unless you have a hard reason not to.\n\n### Go module\n\n```text\n.\n├── cmd/\n│   └── api/\n│       └── main.go\n├── internal/\n│   ├── billing/\n│   └── auth/\n├── pkg/                  # only for libraries other modules should import\n├── api/                  # OpenAPI, proto, generated clients\n├── scripts/\n├── go.mod\n└── README.md\n```\n\n`internal/` is not optional taste — the compiler enforces it. Put nothing in `pkg/` unless an external module should import it.\n\n### Rust\n\n```text\n.\n├── src/\n│   ├── main.rs\n│   ├── lib.rs            # if this is also a library\n│   └── billing/\n├── tests/                # integration tests\n├── benches/\n├── examples/\n├── Cargo.toml\n└── rust-toolchain.toml\n```\n\n### Monorepo\n\nA monorepo is a collection of packages with a single history, not a licence to mix them.\n\n```text\n.\n├── README.md             # the workspace map\n├── package.json          # workspace root: scripts, packageManager, private: true\n├── pnpm-workspace.yaml   # or Cargo workspace / go.work / uv workspace\n├── turbo.json            # or nx.json, moon.yml — if you use an orchestrator\n├── packages/\n│   ├── ui/               # shared library\n│   ├── config/           # shared tsconfig / eslint\n│   └── billing-client/\n├── apps/\n│   ├── web/\n│   └── api/\n├── docs/\n├── scripts/\n└── .github/\n```\n\nRules for monorepos:\n\n- **Apps consume packages. Packages never import apps.**\n- Each package has its own README, even if it is three lines.\n- Shared tooling lives in one place (`packages/config` or the root). Do not copy `tsconfig` twelve times.\n- Boundaries are real: public exports, versioned if you publish, and no reaching into another package's `src/internal`.\n- CI should run only what changed. If every package builds on every commit, the workspace is decorative.\n\nIf you have two apps that do not share code, you do not have a monorepo problem. You have two repos.\n\n## Tests\n\n### Colocate unit tests with the code they prove\n\n```text\nsrc/features/billing/pricing.ts\nsrc/features/billing/pricing.test.ts\n```\n\nA test that is six folders away from its subject will not be updated.\n\n### Keep end-to-end tests separate\n\nE2E tests are a different artefact: slower, broader, and usually run in CI against a running app.\n\n```text\ntests/\n├── e2e/\n├── integration/          # optional, for multi-module tests that are not E2E\n└── fixtures/\n```\n\nor, if the toolchain prefers it:\n\n```text\ne2e/\nfixtures/\n```\n\n### Fixtures and snapshots\n\n- Golden files and fixtures live next to the tests that use them, or in a clearly named `fixtures/` folder.\n- Snapshots are generated output. Review them. Do not let them sprawl into source folders.\n\n### Do not ship tests in production bundles\n\nKeep test files out of published packages. Name them so tooling can exclude them (`*.test.ts`, `*_test.go`, `test_*.py`).\n\n## Documentation\n\nDocs have a job: they survive the people who wrote the code.\n\n```text\ndocs/\n├── README.md             # index of the docs themselves\n├── architecture.md\n├── decisions/            # Architecture Decision Records\n│   ├── 0001-record-architecture-decisions.md\n│   └── 0002-use-feature-folders.md\n├── runbooks/\n└── contributing.md       # if CONTRIBUTING.md at root is only a pointer\n```\n\n### What goes where\n\n| Kind | Home |\n| --- | --- |\n| How to run the project | `README.md` |\n| How to contribute | `CONTRIBUTING.md` |\n| Why we chose X over Y | `docs/decisions/` (ADRs) |\n| How the system fits together | `docs/architecture.md` |\n| What to do at 2am when it breaks | `docs/runbooks/` |\n| Public API for a library | next to the code, generated if you can |\n| Comments in code | *why*, never *what* |\n\n### ADRs\n\nWhen you make a decision that is expensive to reverse, write a short record:\n\n- Title and date\n- Context\n- Decision\n- Consequences\n\nNumber them. Do not rewrite history; add a superseding record.\n\n### Comments\n\nIf a comment restates the next line, delete it. If it explains a constraint, a workaround, or a trade-off the code cannot express, keep it — and consider promoting it to an ADR.\n\n## Configuration and tooling\n\n### Tool config lives at the root\n\nOne `eslint.config.js`, one `tsconfig.json`, one `pyproject.toml`. Apps in a monorepo extend the root; they do not fork it.\n\nDo not scatter `.rc` files without a reason. Prefer the ecosystem's current standard (for example `eslint.config.js` over `.eslintrc`).\n\n### App config is not tool config\n\nRuntime configuration — feature flags, service URLs, limits — belongs in a dedicated module (`src/shared/config/`, `config/`) with a schema and defaults. It does not belong in random constants files.\n\n### Environment\n\n- Commit `.env.example` (or `.env.sample`) with dummy values and comments.\n- Never commit `.env`, `.env.local`, or credential files.\n- Document every variable: name, purpose, default, and whether it is required.\n- Prefer a small set of variables. If you need forty, you probably need a config file with a schema instead.\n\n### Editor and local tooling\n\nA shared `.editorconfig` is enough for most teams. Commit `.vscode/settings.json` or `.vscode/extensions.json` only when the team has agreed the repo should set those defaults. Never commit personal `launch.json` secrets or local history.\n\n## Scripts and CI\n\n### `scripts/` is for humans and machines\n\n```text\nscripts/\n├── bootstrap.sh          # install, generate, migrate — one entry for new machines\n├── check.sh              # the same checks CI runs\n└── release.sh\n```\n\nRules:\n\n- The README's commands call these scripts (or package-manager scripts that wrap them).\n- CI calls the same scripts. Do not maintain two pipelines that drift.\n- Scripts are boring: `set -euo pipefail`, no hidden network, no undeclared prerequisites.\n- If a script is three lines of `npm`, it can live in `package.json`. If it has branches, it belongs in `scripts/`.\n\n### Package scripts as the public interface\n\nFor a Node app, a useful minimum:\n\n```text\ndev       # run locally\nbuild     # production artefact\ntest      # unit / integration\nlint      # static checks\ntypecheck # if the linter does not already\ncheck     # lint + types + tests, what CI and pre-push run\n```\n\nName them plainly. `npm run start:local:debug:v2` is not an interface.\n\n### CI\n\n```text\n.github/\n├── workflows/\n│   ├── ci.yml            # pull requests: check, build\n│   └── release.yml       # tags or main: publish / deploy\n├── CODEOWNERS\n├── PULL_REQUEST_TEMPLATE.md\n└── ISSUE_TEMPLATE/\n```\n\nKeep workflows short. They should install, then call `scripts/check.sh` (or `npm run check`). Logic that exists only in YAML will not be run on laptops, so it will rot.\n\n### CODEOWNERS\n\nMap folders to teams. Feature folders make this honest:\n\n```text\n/src/features/billing/    @acme/billing\n/src/features/auth/       @acme/identity\n/docs/                    @acme/docs\n```\n\nIf you cannot write CODEOWNERS without lying, the folder structure does not match how the organisation works. Fix the folders.\n\n## Assets\n\n- **`public/`** (or `static/`): files served as-is — `favicon.svg`, `robots.txt`, images referenced by URL. Paths are stable.\n- **`src/assets/`**: files the bundler should hash, inline, or transform.\n- **`docs/assets/`**: images that belong to documentation, not the product.\n\nDo not drop a designer's unfiltered export folder into `src/`. Curate. Name files after their role (`logo-mark.svg`, not `Final_v7_REALLY_final.png`).\n\nGenerated media belongs in `dist/` or a CDN, not in git, unless it is a small, versioned artefact the app cannot build without.\n\n## Generated output and third-party code\n\nGit is for source. The following are not source:\n\n| Artefact | Action |\n| --- | --- |\n| `node_modules/`, virtualenvs, `target/` | gitignore |\n| `dist/`, `build/`, `.next/`, `coverage/` | gitignore |\n| Lockfiles | **commit** for applications; libraries may differ |\n| Generated API clients | commit only if generation is awkward for contributors; otherwise generate in `bootstrap` |\n| Vendored third-party code | avoid; if you must, isolate under `vendor/` and document why |\n\nIf a generated file is committed, the command that regenerates it is documented, and CI fails when the committed copy is stale.\n\n## Git hygiene\n\nOrganisation is also history.\n\n- **Small, focused commits** that explain *why*. The folder you touched should match the commit's subject.\n- **Branch names** that map to work (`feat/billing-proration`, `fix/auth-session-expiry`), not `johns-wip-2`.\n- **`main` is sacred.** No force-push, no direct commits if you use pull requests.\n- **Do not commit secrets, and do not rewrite public history to hide them.** Rotate the secret; treat the old one as public.\n- **Keep `main` releasable.** Broken trees on the default branch make every other rule harder to trust.\n\nA useful `.gitignore` baseline:\n\n```text\n# dependencies and virtualenvs\nnode_modules/\n.venv/\nvendor/bundle/\n\n# build and test output\ndist/\nbuild/\ncoverage/\n*.tsbuildinfo\n\n# environment and secrets\n.env\n.env.*\n!.env.example\n\n# editor and OS\n.DS_Store\nThumbs.db\n*.swp\n.idea/\n.vscode/*\n!.vscode/extensions.json\n!.vscode/settings.json\n```\n\nAdjust to the stack. Review it when you add a tool.\n\n## Naming\n\nConsistent names are a layout rule.\n\n| Thing | Convention |\n| --- | --- |\n| Repositories | short, lowercase, hyphenated: `billing-api`, not `Billing_API_NEW` |\n| Folders | same as repos; no spaces, no camelCase in paths |\n| JS/TS files | kebab-case, named after the main export |\n| Python modules | snake_case |\n| Go files | snake_case, package name is the parent folder |\n| Tests | `<name>.test.ts`, `<name>_test.go`, `test_<name>.py` |\n| Docs | kebab-case, descriptive: `auth-session-model.md` |\n| Env vars | `SCREAMING_SNAKE_CASE`, prefixed by the app if they might collide |\n\nNever use `new`, `old`, `temp`, `final`, `wip`, or a date as a folder name. Those names are how graveyards start.\n\n## What not to do\n\nThese patterns show up constantly. They are all avoidable.\n\n- **Type-first folders** (`components/`, `hooks/`, `utils/`) as the primary split.\n- **`misc/`, `common/`, `helpers/`, `shared/utils.ts`** as a destination for thought.\n- **`old/`, `backup/`, `v2/`** sitting next to the live code. Use git history.\n- **Several competing READMEs** at the root (`README.md`, `README.new.md`, `docs.md`).\n- **Copy-pasted config** in every package of a monorepo.\n- **Checking in `node_modules` or build output** \"so it works offline\". Use the lockfile and a cache.\n- **Deep inheritance of folders** that mirrors an org chart from three restructures ago.\n- **Mixing apps at the root** (`frontend/`, `backend/`, `mobile/` with no workspace tooling). That is a monorepo that has not admitted it yet — or three repos in a trench coat.\n- **Putting secrets in example files** \"because they are only dev keys\". Dev keys leak too.\n- **Renaming folders in giant cosmetic PRs.** Layout changes should ship with the feature that needs them, or as a dedicated move with a working tree at every commit.\n\n## A worked example\n\nA mid-size product, one app, a few libraries, a real team:\n\n```text\n.\n├── README.md\n├── LICENSE\n├── CHANGELOG.md\n├── CONTRIBUTING.md\n├── SECURITY.md\n├── package.json\n├── pnpm-lock.yaml\n├── pnpm-workspace.yaml\n├── tsconfig.base.json\n├── .gitignore\n├── .editorconfig\n├── .nvmrc\n├── .env.example\n├── apps/\n│   └── web/\n│       ├── package.json\n│       ├── README.md\n│       ├── src/\n│       │   ├── routes/\n│       │   ├── features/\n│       │   │   ├── auth/\n│       │   │   ├── billing/\n│       │   │   └── catalog/\n│       │   └── shared/\n│       ├── public/\n│       └── tests/e2e/\n├── packages/\n│   ├── ui/\n│   ├── api-client/\n│   └── config/\n├── docs/\n│   ├── architecture.md\n│   ├── decisions/\n│   └── runbooks/\n├── scripts/\n│   ├── bootstrap.sh\n│   └── check.sh\n└── .github/\n    ├── CODEOWNERS\n    └── workflows/\n        └── ci.yml\n```\n\nA new engineer should be able to narrate this tree out loud. If they cannot, keep simplifying.\n\n## Adopting this in an existing repo\n\nDo not boil the ocean. Layout is a garden you tidy in passing.\n\n### Adoption checklist\n\n- [ ] Write or rewrite the README so a stranger can run the project\n- [ ] Add a licence, `.gitignore`, and `.env.example`\n- [ ] Move generated output out of git if it slipped in\n- [ ] Collapse the root: only lobby files remain\n- [ ] Introduce `src/` (or the language's equivalent) if application code is loose at the root\n- [ ] Pick feature folders for the next new work; stop adding to type folders\n- [ ] Colocate new tests with their subject\n- [ ] Put automation in `scripts/` and make CI call it\n- [ ] Add `docs/decisions/` and record the layout choice itself as ADR 0001\n- [ ] Add CODEOWNERS that match the folders\n- [ ] Delete `old/`, `misc/`, and duplicate READMEs\n- [ ] Ban new files at the root in review, except when the lobby actually needs them\n\nMove code when you touch it. A six-month migration that only moves files will stall; a rule that \"new work follows the standard\" will not.\n\n## Review questions\n\nUse these in code review. If the answer is no, request a move before merge.\n\n1. Could a new contributor guess this file's folder from its name?\n2. Does this change sit inside one feature, or did it scatter?\n3. Did we add a file to the root that is not a lobby file?\n4. Did we add a helper to `shared/` that only one feature uses?\n5. Is anything generated being committed without a regenerate command?\n6. Would CODEOWNERS ping the right team?\n\n## Further reading\n\nThese are the documents this standard is in conversation with. Steal from them freely.\n\n- [A Philosophy of Software Design](https://web.stanford.edu/~ouster/cgi-bin/book.php) — modules as secrets, not as file types\n- [Architecture Decision Records](https://adr.github.io/) — how to write the exceptions\n- [Keep a Changelog](https://keepachangelog.com/) — what belongs in `CHANGELOG.md`\n- [Conventional Commits](https://www.conventionalcommits.org/) — how history should read\n- [The Twelve-Factor App](https://12factor.net/) — config, build, and release as separate concerns\n- [Go project layout](https://go.dev/doc/modules/layout) — language-idiomatic trees beat generic ones\n- [Semantic Versioning](https://semver.org/) — when a folder is also a published package\n";
function Home() {
	return /* @__PURE__ */ (0, import_jsx_runtime.jsx)(GuideReader, { source: repo_organization_default });
}
//#endregion
export { Home as component };
