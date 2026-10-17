---
name: Liveness Verification Console
colors:
  surface: '#f8f9ff'
  surface-dim: '#cbdbf5'
  surface-bright: '#f8f9ff'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#eff4ff'
  surface-container: '#e5eeff'
  surface-container-high: '#dce9ff'
  surface-container-highest: '#d3e4fe'
  on-surface: '#0b1c30'
  on-surface-variant: '#434654'
  inverse-surface: '#213145'
  inverse-on-surface: '#eaf1ff'
  outline: '#737685'
  outline-variant: '#c3c6d6'
  surface-tint: '#1c55cf'
  primary: '#003eaa'
  on-primary: '#ffffff'
  primary-container: '#1e56d0'
  on-primary-container: '#cfd9ff'
  inverse-primary: '#b4c5ff'
  secondary: '#565e74'
  on-secondary: '#ffffff'
  secondary-container: '#dae2fd'
  on-secondary-container: '#5c647a'
  tertiary: '#004496'
  on-tertiary: '#ffffff'
  tertiary-container: '#005bc4'
  on-tertiary-container: '#cbdaff'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#dbe1ff'
  primary-fixed-dim: '#b4c5ff'
  on-primary-fixed: '#00174b'
  on-primary-fixed-variant: '#003ea8'
  secondary-fixed: '#dae2fd'
  secondary-fixed-dim: '#bec6e0'
  on-secondary-fixed: '#131b2e'
  on-secondary-fixed-variant: '#3f465c'
  tertiary-fixed: '#d8e2ff'
  tertiary-fixed-dim: '#adc6ff'
  on-tertiary-fixed: '#001a42'
  on-tertiary-fixed-variant: '#004395'
  background: '#f8f9ff'
  on-background: '#0b1c30'
  surface-variant: '#d3e4fe'
  bg-page: '#F8FAFC'
  bg-surface: '#FFFFFF'
  bg-subtle: '#F1F5F9'
  border-default: '#E2E8F0'
  border-strong: '#CBD5E1'
  text-primary: '#0F172A'
  text-secondary: '#475569'
  text-muted: '#64748B'
  status-success: '#15803D'
  status-success-bg: '#DCFCE7'
  status-success-border: '#86EFAC'
  status-danger: '#B91C1C'
  status-danger-bg: '#FEE2E2'
  status-danger-border: '#FCA5A5'
  status-warning: '#B45309'
  status-warning-bg: '#FEF3C7'
  status-warning-border: '#FCD34D'
  status-neutral: '#475569'
  status-neutral-bg: '#F1F5F9'
  status-neutral-border: '#CBD5E1'
typography:
  display:
    fontFamily: Inter
    fontSize: 32px
    fontWeight: '700'
    lineHeight: 40px
    letterSpacing: -0.02em
  display-mobile:
    fontFamily: Inter
    fontSize: 26px
    fontWeight: '700'
    lineHeight: 32px
    letterSpacing: -0.015em
  headline-lg:
    fontFamily: Inter
    fontSize: 24px
    fontWeight: '600'
    lineHeight: 32px
    letterSpacing: -0.01em
  headline-lg-mobile:
    fontFamily: Inter
    fontSize: 20px
    fontWeight: '600'
    lineHeight: 28px
    letterSpacing: -0.01em
  headline-md:
    fontFamily: Inter
    fontSize: 18px
    fontWeight: '600'
    lineHeight: 24px
  headline-sm:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '600'
    lineHeight: 22px
  body-lg:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  body-md:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
  body-sm:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
  label-lg:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '500'
    lineHeight: 20px
  label-md:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '500'
    lineHeight: 16px
  label-sm:
    fontFamily: Inter
    fontSize: 11px
    fontWeight: '600'
    lineHeight: 14px
    letterSpacing: 0.02em
  code:
    fontFamily: JetBrains Mono
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  gutter: 1.5rem
  gutter-mobile: 1rem
  margin: 2rem
  margin-mobile: 1rem
  space-2xs: 0.125rem
  space-xs: 0.25rem
  space-sm: 0.5rem
  space-md: 1rem
  space-lg: 1.5rem
  space-xl: 2rem
  space-2xl: 3rem
---

## Brand & Style

This design system delivers an institutional, mission-critical interface engineered for biometric identity verification, multi-role authentication (Resident, Operator, Supervisor), and security administration. It balances uncompromising enterprise rigor with approachable, low-cognitive-load user flows during high-pressure physical capture scenarios.

### Personality & Tone
- **Authoritative & Trustworthy:** Clean, precise lines, neutral structural backdrops, and standardized state markers foster confidence in mission-critical national identity systems.
- **Calm & Focused:** Screen clutter is eliminated during capture flows; users receive singular, unfragmented instructions.
- **Direct & Transparent:** System states (initializing, challenge, retry, terminal lock) are articulated clearly without exposing internal neural net scores, PAD models, or proprietary heuristics to end users.

### Design Movement: Modern Institutional Enterprise
Drawing from contemporary high-utility design, this system combines:
- Crisp structural 1px borders (`#E2E8F0` / `#CBD5E1`) separating cards, tables, and dialog segments.
- Elevated pure white (`#FFFFFF`) containers over a tinted, anti-glare canvas (`#F8FAFC`).
- High-contrast, dark slate typography (`#0F172A`) paired with a vibrant, accessible digital cobalt primary accent (`#1E56D0`).
- Semantic status pills with high readability dot indicators, providing immediate recognition across high-throughput physical operator stations.

## Colors

The color palette prioritizes high-contrast legibility, accessibility (WCAG AA/AAA compliance across all interactive and status markers), and swift identification of biometric processing states.

### Palette Architecture
- **Primary (`#1E56D0`):** The signature institutional blue. Drives primary interactive actions, selected tab indicators, active camera states, and key identity badges.
- **Secondary (`#0F172A`):** Deep slate neutral serving as high-contrast body typography, dark primary iconography, and structural anchors.
- **Tertiary (`#3B82F6`):** Lighter dynamic blue used for focus rings, subtle interactive hover states, and informational callouts.
- **Canvas & Surface Neutrals:**
  - `bg-page` (`#F8FAFC`): Soft cool canvas reducing eye fatigue in dim enrollment workstations.
  - `bg-surface` (`#FFFFFF`): Crisp white cards, modals, and container shells.
  - `bg-subtle` (`#F1F5F9`): Table headers, inactive pill backgrounds, and disabled controls.
  - `border-default` (`#E2E8F0`): Subtle bounding line for cards, inputs, and dividers.

### Semantic Status Markers
- **Passed / Intact (`#15803D` on `#DCFCE7`):** Signals successful verification, valid cryptographic chains, and passed challenge hurdles.
- **Failed / PAD Alert / Danger (`#B91C1C` on `#FEE2E2`):** Denotes presentation attacks, spoof detections, failed identity checks, and critical timeouts.
- **Active / Checking / Warning (`#B45309` on `#FEF3C7`):** Communicates ongoing capture steps, pending active challenges (blink, turn, smile), or unsaved policy edits.
- **Expired / Inactive (`#475569` on `#F1F5F9`):** Represents expired audit sessions, disconnected sensors, or stale tokens.

## Typography

The design system standardizes on **Inter** for all UI surfaces across Desktop (JavaFX), Mobile (Flutter), and Web Console to guarantee cross-platform metric equivalence and legibility under varied hardware DPIs. **JetBrains Mono** is specified strictly for cryptographic hashes, session nonces, and hardware diagnostic outputs.

### Typographic Hierarchy Rules
- **Display & Main Headings:** Used sparingly on welcome screens, multi-role selection boards, and core flow transitions. Tight letter-spacing (`-0.01em` to `-0.02em`) preserves compactness.
- **Section & Card Titles:** `headline-md` and `headline-sm` designate administrative widgets, camera overlay instruction panels, and verification card titles.
- **Body & Prompts:** `body-md` is the primary interaction level for challenge prompts (e.g., "Look straight at the camera", "Keep your face inside the oval").
- **Status & Metadata Labels:** `label-md` and `label-sm` are leveraged for badges, navigation items, audit chain flags, and session attributes.

## Layout & Spacing

The layout is built upon an 8pt base grid with a 12-column responsive fluid container capped at `1440px` maximum content width for administrative screens, and a centered single-focus canvas (`640px` max width) for the biometric capture gate.

### Layout Breakpoints
- **Desktop (>= 1024px):** 12-column grid, `margin: 2rem`, `gutter: 1.5rem`. Split layout for capture views (camera feed 7 cols, instruction/audit panel 5 cols).
- **Tablet (768px – 1023px):** 8-column grid, `margin: 1.5rem`, `gutter: 1rem`. Controls stack below camera viewport.
- **Mobile (< 768px):** 4-column fluid layout, `margin: 1rem`, `gutter: 1rem`. Full viewport camera feed with non-intrusive floating guidance cards and persistent bottom action sheet.

### Spacing Application
- `space-xs` (4px) & `space-sm` (8px): Micro-gaps between status dot and text inside pills; padding inside compact table cells and input icons.
- `space-md` (16px): Internal padding for cards, forms, prompt banners, and button padding.
- `space-lg` (24px): Gap between distinct form groups, card stacks, and multi-tier role cards.
- `space-xl` (32px) & `space-2xl` (48px): Section separation and master view header offsets.

## Elevation & Depth

This system avoids heavy, blurred drop shadows in favor of crisp structural borders and subtle, low-opacity ambient shadows. This reinforces clean enterprise authority and ensures predictable rendering on native Android views and JavaFX desktop clients.

### Layering Hierarchy
- **Level 0 (Flat / Canvas):** `#F8FAFC` base application background. No shadow.
- **Level 1 (Card & Content Surface):** `#FFFFFF` fill with a 1px border (`#E2E8F0`) and an ambient micro-shadow: `0 1px 3px 0 rgba(15, 23, 42, 0.05), 0 1px 2px -1px rgba(15, 23, 42, 0.05)`. Applied to standard cards, data tables, and input panels.
- **Level 2 (Interactive Floating & Popovers):** `#FFFFFF` fill with `0 4px 6px -1px rgba(15, 23, 42, 0.07), 0 2px 4px -2px rgba(15, 23, 42, 0.05)`. Applied to active dropdowns, session filter pickers, and hovering cards.
- **Level 3 (Modals & Overlays):** `#FFFFFF` surface with `0 20px 25px -5px rgba(15, 23, 42, 0.1), 0 8px 10px -6px rgba(15, 23, 42, 0.06)` against a dimmed backdrop (`rgba(15, 23, 42, 0.4)`). Applied to supervisor sign-in dialogs and terminal error overrides.

## Shapes

The shape system employs moderate, balanced rounding to convey modern precision without feeling casual or toy-like.

- **Base Components (`rounded`, 8px / 0.5rem):** Standard buttons, text input fields, notification banners, role selection cards, and data containers.
- **Large Panels (`rounded-lg`, 12px / 0.75rem):** Master viewport wrappers, camera preview containers, and supervisor modal sheets.
- **Full Rounded / Pills (`rounded-full`, 9999px):** Status badges (Passed, Failed, Active, Expired), step numeric indicators (1, 2, 3), and avatar initial tokens.
- **Camera Guide Oval:** Dynamic vector overlay with a 1:1.35 aspect ratio and a dashed 2px vector border (`rgba(255, 255, 255, 0.8)` or `#1E56D0` when locked) to guide user alignment.

## Components

### Buttons
- **Primary:** Background `#1E56D0`, text `#FFFFFF`, border none, 8px radius. Height 40px (desktop), 48px (mobile touch target). Hover: `#1B4DBA`. Focus: 2px offset ring `#3B82F6`.
- **Secondary / Ghost:** Background `#FFFFFF`, text `#0F172A`, 1px border `#E2E8F0`. Hover: `#F8FAFC` and border `#CBD5E1`.
- **Destructive / Stop:** Background `#B91C1C`, text `#FFFFFF`. Hover: `#991B1B`. Used for stopping live gates or rejecting sessions.
- **Disabled:** Background `#F1F5F9`, text `#94A3B8`, border `#E2E8F0`, pointer-events none.

### Status Pills
Pills communicate binary and intermediate states across audit grids and verification cards. Format: `px-2.5 py-0.5`, rounded-full, `font-size: 12px`, `font-weight: 500`. Includes a 6px diameter filled circular dot indicator on the leading edge.
- **Passed / Intact:** Background `#DCFCE7`, text `#15803D`, dot `#15803D`.
- **Failed / Not Verified:** Background `#FEE2E2`, text `#B91C1C`, dot `#B91C1C`.
- **Active / Checking:** Background `#FEF3C7`, text `#B45309`, dot `#B45309`.
- **Expired:** Background `#F1F5F9`, text `#475569`, dot `#64748B`.
- **Role Badges (Operator / Supervisor / Resident):** Background `#EFF6FF`, text `#1E56D0`, border `#BFDBFE`.

### Feedback & Status Banners
Full-width or card-anchored notification banners with 1px border and 8px radius.
- **Success Banner:** Background `#F0FDF4`, border `#86EFAC`, leading check icon in `#15803D`, title `headline-sm` `#14532D`, body `body-sm` `#166534`.
- **Failure / PAD Alert Banner:** Background `#FEF2F2`, border `#FCA5A5`, alert icon in `#B91C1C`, title `#7F1D1D`, body `#991B1B`.
- **In-Progress Banner:** Background `#FEFCE8`, border `#FDE047`, spinner or timer icon in `#A16207`, title `#713F12`, body `#854D0E`.

### Biometric Camera Surface
- A hardened slate container (`#020617` or `#0F172A`) containing the live video preview stream.
- An SVG vector oval guide overlay centered vertically and horizontally. Guide color transitions from dashed white (`#FFFFFF`) during positioning to solid vibrant blue (`#1E56D0`) upon face acquisition, and green (`#22C55E`) upon successful PAD completion.
- Position guidance badge anchored at the bottom of the viewfinder: semi-transparent pill (`rgba(15, 23, 42, 0.75)` backdrop-filter blur 4px, text `#FFFFFF`).

### Cards & Data Tables
- **Cards:** Crisp `#FFFFFF` surface, 1px `#E2E8F0` border, `space-lg` padding, rounded-lg.
- **Tables:** Monospace values for hashes/timestamps, clean dividing lines (`#F1F5F9`), header row filled with `#F8FAFC`, uppercase `label-sm` column labels with sorting toggles.