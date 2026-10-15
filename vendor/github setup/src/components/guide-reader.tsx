import { useEffect, useMemo, useState, type ReactNode } from "react";
import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
import {
  Check,
  Copy,
  Download,
  FolderTree,
  Menu,
  Search,
  X,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  downloadText,
  flattenText,
  parseToc,
  slugify,
  splitGuide,
  type TocItem,
} from "@/lib/guide";
import { cn } from "@/lib/utils";

const CHECKLIST_KEY = "repo-order-checklist";

type GuideReaderProps = {
  source: string;
};

export function GuideReader({ source }: GuideReaderProps) {
  const { title, body } = useMemo(() => splitGuide(source), [source]);
  const toc = useMemo(() => parseToc(source), [source]);
  const [query, setQuery] = useState("");
  const [activeId, setActiveId] = useState(toc[0]?.id ?? "");
  const [menuOpen, setMenuOpen] = useState(false);
  const [copied, setCopied] = useState(false);
  const [checked, setChecked] = useState<Record<string, boolean>>({});

  useEffect(() => {
    try {
      const raw = localStorage.getItem(CHECKLIST_KEY);
      if (raw) setChecked(JSON.parse(raw) as Record<string, boolean>);
    } catch {
      /* ignore broken local state */
    }
  }, []);

  useEffect(() => {
    const headings = Array.from(
      document.querySelectorAll<HTMLElement>(".guide-prose h2, .guide-prose h3"),
    );
    if (headings.length === 0) return;

    const observer = new IntersectionObserver(
      (entries) => {
        const visible = entries
          .filter((entry) => entry.isIntersecting)
          .sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top);
        const id = visible[0]?.target.id;
        if (id) setActiveId(id);
      },
      { rootMargin: "-20% 0px -65% 0px", threshold: [0, 1] },
    );

    headings.forEach((heading) => observer.observe(heading));
    return () => observer.disconnect();
  }, [body]);

  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if (event.key === "Escape") setMenuOpen(false);
      if (event.key === "/" && !(event.target instanceof HTMLInputElement)) {
        event.preventDefault();
        document.getElementById("guide-search")?.focus();
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const filteredToc = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return toc;
    return toc.filter((item) => item.text.toLowerCase().includes(needle));
  }, [query, toc]);

  function toggleCheck(id: string) {
    setChecked((prev) => {
      const next = { ...prev, [id]: !prev[id] };
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
    downloadText(
      "REPO_ORGANIZATION.md",
      source,
      "text/markdown;charset=utf-8",
    );
  }

  return (
    <div className="min-h-dvh bg-bg text-fg">
      <header className="no-print sticky top-0 z-30 border-b border-border bg-bg/90 backdrop-blur-sm">
        <div className="mx-auto flex h-14 max-w-6xl items-center gap-3 px-4 sm:px-6">
          <Button
            variant="ghost"
            size="icon"
            className="lg:hidden"
            aria-label="Open contents"
            onClick={() => setMenuOpen(true)}
          >
            <Menu className="size-5" />
          </Button>
          <a href="#top" className="flex min-w-0 items-center gap-2.5">
            <span className="flex size-8 items-center justify-center rounded-md bg-primary text-primary-fg">
              <FolderTree className="size-4" strokeWidth={1.75} />
            </span>
            <span className="min-w-0">
              <span className="block truncate text-sm font-semibold tracking-tight">
                Repo Order
              </span>
              <span className="hidden text-xs text-muted sm:block">
                A handbook for repository layout
              </span>
            </span>
          </a>
          <div className="ml-auto flex items-center gap-2">
            <Button variant="ghost" onClick={() => void copyMarkdown()}>
              {copied ? <Check className="size-4" /> : <Copy className="size-4" />}
              <span className="hidden sm:inline">{copied ? "Copied" : "Copy"}</span>
            </Button>
            <Button onClick={downloadMarkdown}>
              <Download className="size-4" />
              <span className="hidden sm:inline">Download .md</span>
              <span className="sm:hidden">.md</span>
            </Button>
          </div>
        </div>
      </header>

      <div className="mx-auto flex max-w-6xl">
        <aside className="no-print hidden w-64 shrink-0 border-r border-border lg:block">
          <div className="toc-pane sticky top-14 overflow-y-auto px-4 py-6">
            <TocPanel
              query={query}
              onQuery={setQuery}
              items={filteredToc}
              activeId={activeId}
              onJump={() => undefined}
            />
          </div>
        </aside>

        {menuOpen ? (
          <div className="no-print fixed inset-0 z-40 lg:hidden">
            <button
              type="button"
              className="absolute inset-0 bg-fg/30"
              aria-label="Close contents"
              onClick={() => setMenuOpen(false)}
            />
            <aside className="absolute inset-y-0 left-0 flex w-[min(20rem,88vw)] flex-col bg-surface shadow-border">
              <div className="flex h-14 items-center justify-between border-b border-border px-3">
                <p className="px-2 text-sm font-semibold">Contents</p>
                <Button
                  variant="ghost"
                  size="icon"
                  aria-label="Close contents"
                  onClick={() => setMenuOpen(false)}
                >
                  <X className="size-5" />
                </Button>
              </div>
              <div className="flex-1 overflow-y-auto px-4 py-5">
                <TocPanel
                  query={query}
                  onQuery={setQuery}
                  items={filteredToc}
                  activeId={activeId}
                  onJump={() => setMenuOpen(false)}
                />
              </div>
            </aside>
          </div>
        ) : null}

        <main id="top" className="min-w-0 flex-1 overflow-x-hidden px-5 py-10 sm:px-8 lg:px-12 lg:py-14">
          <p className="text-xs font-medium tracking-widest text-muted uppercase">
            Engineering standard
          </p>
          <h1 className="font-display mt-3 max-w-prose text-4xl font-medium leading-tight tracking-tight sm:text-5xl">
            {title}
          </h1>
          <p className="mt-5 max-w-prose text-lg leading-relaxed text-muted">
            Opinionated layout rules for source, tests, docs, scripts, and the
            files that belong at the root — written so a stranger can navigate
            the tree in five minutes.
          </p>

          <article className="guide-prose mt-10 w-full min-w-0 max-w-prose">
            <Markdown
              remarkPlugins={[remarkGfm]}
              components={{
                h1: () => null,
                h2: ({ children }) => (
                  <h2 id={slugify(flattenText(children))}>{children}</h2>
                ),
                h3: ({ children }) => (
                  <h3 id={slugify(flattenText(children))}>{children}</h3>
                ),
                pre: ({ children }) => <CodeBlock>{children}</CodeBlock>,
                table: ({ children }) => (
                  <div className="my-5 overflow-x-auto">
                    <table>{children}</table>
                  </div>
                ),
                a: ({ href, children }) => (
                  <a href={href} target={href?.startsWith("http") ? "_blank" : undefined} rel={href?.startsWith("http") ? "noreferrer" : undefined}>
                    {children}
                  </a>
                ),
                li: ({ children, className, ...props }) => {
                  const isTask = className?.includes("task-list-item");
                  if (!isTask) {
                    return (
                      <li className={className} {...props}>
                        {children}
                      </li>
                    );
                  }
                  const id = slugify(flattenText(children));
                  return (
                    <li className={cn(className, "task-item")} {...props}>
                      <label className="flex cursor-pointer items-start gap-3">
                        <input
                          type="checkbox"
                          className="mt-1 size-4 shrink-0 accent-primary"
                          checked={Boolean(checked[id])}
                          onChange={() => toggleCheck(id)}
                        />
                        <span className={checked[id] ? "text-muted line-through" : undefined}>
                          {stripLeadingCheckbox(children)}
                        </span>
                      </label>
                    </li>
                  );
                },
              }}
            >
              {body}
            </Markdown>
          </article>
        </main>
      </div>
    </div>
  );
}

function TocPanel({
  query,
  onQuery,
  items,
  activeId,
  onJump,
}: {
  query: string;
  onQuery: (value: string) => void;
  items: TocItem[];
  activeId: string;
  onJump: () => void;
}) {
  return (
    <div>
      <label className="relative block">
        <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-subtle" />
        <input
          id="guide-search"
          type="search"
          value={query}
          onChange={(event) => onQuery(event.target.value)}
          placeholder="Search sections"
          className="h-11 w-full rounded-md border border-border bg-surface pr-3 pl-10 text-sm text-fg outline-none ring-primary/30 placeholder:text-subtle focus:ring-2"
        />
      </label>
      <p className="mt-6 mb-2 px-1 text-xs font-semibold tracking-widest text-muted uppercase">
        Contents
      </p>
      {items.length === 0 ? (
        <p className="px-1 text-sm text-muted">No sections match.</p>
      ) : (
        <nav aria-label="Table of contents">
          <ul className="flex flex-col gap-0.5">
            {items.map((item) => (
              <li key={item.id}>
                <a
                  href={`#${item.id}`}
                  onClick={onJump}
                  className={cn(
                    "block rounded-md py-1.5 text-sm leading-snug transition-colors duration-150",
                    item.level === 3 ? "px-1 pl-4" : "px-1 font-medium",
                    activeId === item.id
                      ? "bg-code text-fg"
                      : "text-muted hover:bg-code/70 hover:text-fg",
                  )}
                >
                  {item.text}
                </a>
              </li>
            ))}
          </ul>
        </nav>
      )}
    </div>
  );
}

function CodeBlock({ children }: { children: ReactNode }) {
  const [copied, setCopied] = useState(false);
  const text = flattenText(children).replace(/\n$/, "");

  return (
    <div className="group relative my-5 w-full min-w-0 overflow-x-auto rounded-lg bg-code">
      <button
        type="button"
        className="absolute top-2 right-2 inline-flex size-9 items-center justify-center rounded-md text-muted opacity-100 transition-opacity duration-150 hover:bg-rail hover:text-fg sm:opacity-0 sm:group-hover:opacity-100"
        aria-label={copied ? "Copied" : "Copy code"}
        onClick={() => {
          void navigator.clipboard.writeText(text);
          setCopied(true);
          window.setTimeout(() => setCopied(false), 1500);
        }}
      >
        {copied ? <Check className="size-4" /> : <Copy className="size-4" />}
      </button>
      <pre className="min-w-0 overflow-x-auto p-4 font-mono text-fg">{children}</pre>
    </div>
  );
}

function stripLeadingCheckbox(children: ReactNode): ReactNode {
  if (!Array.isArray(children)) return children;
  return children.filter((child) => {
    if (typeof child !== "object" || child == null || !("props" in child)) {
      return true;
    }
    const props = (child as { props?: { type?: string } }).props;
    return props?.type !== "checkbox";
  });
}
