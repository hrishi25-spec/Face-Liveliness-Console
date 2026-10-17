# assets/

Optional image slots for the top-level [`README.md`](../README.md). None of these
files exist, and the README **does not reference them today** — it uses text badges
and a Mermaid diagram only. This folder is a place to drop them if the page ever
grows a media section; add the markdown reference in the same change that adds the
file, so nothing points at a missing image.

| File | Recommended size | What it should show |
|------|------------------|---------------------|
| `banner.png` | 1280×640 | Project banner. Title "Face Liveliness Console" (the current project name) over a dark or neutral background. Keep the safe area centered so the title is not cropped on wide screens. |
| `architecture.png` | 1600×900 | Layered architecture: API layer → services → engine → pluggable backends → device adapters. Match the package tree in the README. |
| `decision-flow.png` | 1600×900 | The decision flow: Session → Frame → Passive + PAD → {Proceed \| Escalate to Active \| Reject} → Challenge → {Proceed \| Retry \| Reject}. A rendered version of the Mermaid diagram in the README. |
| `swagger-ui.png` | 1600×1000 | Screenshot of the running Swagger UI at `/swagger-ui/index.html`, showing the session/frame/challenge endpoints expanded. |
| `demo.gif` | 800×600 (≤ 10 MB) | Short screen recording (10–20 s) of the browser console at `/` driving a resident session through a successful passive pass and one active challenge. |

## Conventions

- Prefer PNG for diagrams (crisp text) and GIF/MP4-to-GIF for the demo.
- If you add dark/light variants, name them `banner-dark.png` / `banner-light.png`
  and reference them with a `<picture>` element in the README.
- Keep total assets under ~15 MB so the repository stays light to clone.
- Do not commit screenshots that contain live personal data, camera frames, or
  secrets.
