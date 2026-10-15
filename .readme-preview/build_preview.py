"""Render README.md to a standalone, GitHub-like HTML page for local preview.

Run:  python .readme-preview/build_preview.py
Out:  .readme-preview/readme.html
"""
import pathlib
import markdown

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "README.md"
OUT = ROOT / ".readme-preview" / "readme.html"

body = markdown.markdown(
    SRC.read_text(encoding="utf-8"),
    extensions=["tables", "fenced_code", "md_in_html", "attr_list", "sane_lists"],
    output_format="html5",
)

CSS = """
:root { color-scheme: light dark; }
* { box-sizing: border-box; }
body {
  margin: 0; padding: 32px 16px 80px;
  font: 16px/1.6 -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif;
  background: #ffffff; color: #1f2328;
}
.markdown-body { max-width: 900px; margin: 0 auto; }
.markdown-body h1 { font-size: 2em; border-bottom: 1px solid #d1d9e0; padding-bottom: .3em; }
.markdown-body h2 { font-size: 1.5em; border-bottom: 1px solid #d1d9e0; padding-bottom: .3em; margin-top: 1.6em; }
.markdown-body h3 { font-size: 1.25em; margin-top: 1.4em; }
.markdown-body code {
  background: #eff1f3; padding: .2em .4em; border-radius: 6px;
  font: 85% ui-monospace, SFMono-Regular, Consolas, monospace;
}
.markdown-body pre {
  background: #f6f8fa; padding: 16px; border-radius: 6px; overflow: auto;
}
.markdown-body pre code { background: none; padding: 0; font-size: 85%; }
.markdown-body table { border-collapse: collapse; display: block; overflow: auto; margin: 1em 0; }
.markdown-body th, .markdown-body td { border: 1px solid #d1d9e0; padding: 6px 13px; }
.markdown-body th { background: #f6f8fa; }
.markdown-body blockquote {
  margin: 1em 0; padding: .5em 1em; color: #59636e;
  border-left: .25em solid #d1d9e0; background: #f6f8fa; border-radius: 0 6px 6px 0;
}
.markdown-body img { max-width: 100%; height: auto; }
.markdown-body details {
  border: 1px solid #d1d9e0; border-radius: 6px; padding: .5em 1em; margin: 1em 0; background: #fbfcfd;
}
.markdown-body summary { cursor: pointer; font-weight: 600; }
.markdown-body hr { border: 0; border-top: 1px solid #d1d9e0; margin: 2em 0; }
.markdown-body a { color: #0969da; text-decoration: none; }
.markdown-body a:hover { text-decoration: underline; }
.banner-note { max-width: 900px; margin: 0 auto 24px; font-size: 13px; color: #b35900;
  background: #fff8e6; border: 1px solid #ffd591; border-radius: 6px; padding: 10px 14px; }
@media (prefers-color-scheme: dark) {
  body { background: #0d1117; color: #e6edf3; }
  .markdown-body h1, .markdown-body h2 { border-color: #30363d; }
  .markdown-body code { background: #262c36; }
  .markdown-body pre { background: #161b22; }
  .markdown-body th, .markdown-body td { border-color: #30363d; }
  .markdown-body th { background: #161b22; }
  .markdown-body blockquote { background: #161b22; color: #9198a1; border-color: #30363d; }
  .markdown-body details { background: #161b22; border-color: #30363d; }
  .markdown-body a { color: #4493f8; }
  .banner-note { background: #2b2109; border-color: #9e6a03; color: #e3b341; }
}
"""

HTML = f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>README preview — MOSIP Face Liveness &amp; PAD Service</title>
<style>{CSS}</style>
</head>
<body>
<div class="banner-note">
  Local preview of <code>README.md</code>. Image slots (banner, diagrams, Swagger UI, demo)
  are placeholders and will appear broken until real files are added to <code>assets/</code>.
  The Mermaid diagram is shown as code because no renderer is bundled.
</div>
<div class="markdown-body">
{body}
</div>
</body>
</html>
"""

OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(HTML, encoding="utf-8")
print(f"wrote {OUT}")
print(f"bytes: {len(HTML)}")
