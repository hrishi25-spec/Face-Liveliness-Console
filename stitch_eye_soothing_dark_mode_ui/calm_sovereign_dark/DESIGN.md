---
name: Calm Sovereign Dark
colors:
  surface: '#051424'
  surface-dim: '#051424'
  surface-bright: '#2c3a4c'
  surface-container-lowest: '#010f1f'
  surface-container-low: '#0d1c2d'
  surface-container: '#122131'
  surface-container-high: '#1c2b3c'
  surface-container-highest: '#273647'
  on-surface: '#d4e4fa'
  on-surface-variant: '#c2c6d6'
  inverse-surface: '#d4e4fa'
  inverse-on-surface: '#233143'
  outline: '#8c909f'
  outline-variant: '#424754'
  surface-tint: '#adc6ff'
  primary: '#adc6ff'
  on-primary: '#002e6a'
  primary-container: '#4d8eff'
  on-primary-container: '#00285d'
  inverse-primary: '#005ac2'
  secondary: '#a4c9ff'
  on-secondary: '#00315d'
  secondary-container: '#0267b8'
  on-secondary-container: '#d6e5ff'
  tertiary: '#7bd0ff'
  on-tertiary: '#00354a'
  tertiary-container: '#009bd1'
  on-tertiary-container: '#002d40'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#d8e2ff'
  primary-fixed-dim: '#adc6ff'
  on-primary-fixed: '#001a42'
  on-primary-fixed-variant: '#004395'
  secondary-fixed: '#d4e3ff'
  secondary-fixed-dim: '#a4c9ff'
  on-secondary-fixed: '#001c39'
  on-secondary-fixed-variant: '#004883'
  tertiary-fixed: '#c4e7ff'
  tertiary-fixed-dim: '#7bd0ff'
  on-tertiary-fixed: '#001e2c'
  on-tertiary-fixed-variant: '#004c69'
  background: '#051424'
  on-background: '#d4e4fa'
  surface-variant: '#273647'
  canvas-bg: '#090D16'
  surface-base: '#0B0F19'
  surface-raised: '#111827'
  surface-elevated: '#161F30'
  surface-camera: '#030712'
  border-subtle: '#1F293D'
  border-strong: '#2D3748'
  text-primary: '#F1F5F9'
  text-secondary: '#94A3B8'
  text-muted: '#64748B'
  status-success-text: '#34D399'
  status-success-bg: rgba(16, 185, 129, 0.12)
  status-success-border: rgba(16, 185, 129, 0.28)
  status-danger-text: '#F87171'
  status-danger-bg: rgba(244, 63, 94, 0.12)
  status-danger-border: rgba(244, 63, 94, 0.28)
  status-warning-text: '#FBBF24'
  status-warning-bg: rgba(245, 158, 11, 0.12)
  status-warning-border: rgba(245, 158, 11, 0.28)
  status-info-text: '#38BDF8'
  status-info-bg: rgba(56, 189, 248, 0.12)
  status-info-border: rgba(56, 189, 248, 0.28)
  accent-glow: rgba(59, 130, 246, 0.22)
typography:
  headline-xl:
    fontFamily: Inter
    fontSize: 28px
    fontWeight: '600'
    lineHeight: 36px
    letterSpacing: -0.02em
  headline-xl-mobile:
    fontFamily: Inter
    fontSize: 22px
    fontWeight: '600'
    lineHeight: 28px
    letterSpacing: -0.01em
  headline-lg:
    fontFamily: Inter
    fontSize: 20px
    fontWeight: '600'
    lineHeight: 28px
    letterSpacing: -0.01em
  headline-md:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '600'
    lineHeight: 24px
  body-lg:
    fontFamily: Inter
    fontSize: 15px
    fontWeight: '400'
    lineHeight: 24px
  body-md:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
  body-sm:
    fontFamily: Inter
    fontSize: 13px
    fontWeight: '400'
    lineHeight: 18px
  label-md:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '500'
    lineHeight: 16px
  label-caps:
    fontFamily: Inter
    fontSize: 11px
    fontWeight: '600'
    lineHeight: 16px
    letterSpacing: 0.06em
  telemetry-lg:
    fontFamily: JetBrains Mono
    fontSize: 14px
    fontWeight: '500'
    lineHeight: 20px
  telemetry-md:
    fontFamily: JetBrains Mono
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
  telemetry-sm:
    fontFamily: JetBrains Mono
    fontSize: 11px
    fontWeight: '400'
    lineHeight: 14px
    letterSpacing: 0.02em
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
  space-xs: 0.25rem
  space-sm: 0.5rem
  space-md: 1rem
  space-lg: 1.5rem
  space-xl: 2rem
  space-2xl: 3rem
---

## Brand & Style

This design system establishes an eye-soothing, modern institutional enterprise dark mode designed specifically for identity verification, Presentation Attack Detection (PAD), and biometric enrolment consoles. 

Operating workstations for border control, national identity registration, and supervisor audits endure prolonged user interaction under controlled, often low-light indoor environments. The visual language purposefully discards stark pitch-black (#000000) vs. harsh pure-white (#FFFFFF) contrasts in favor of low-strain deep slate, obsidian, and midnight tones paired with soft off-white typography and luminescent cobalt signals.

The design philosophy balances **Institutional Rigor** with **Tactile Modern Telemetry**:
- **Eye-Soothing Low Strain:** Surfaces layer naturally from deep midnight foundations to elevated, softly tinted charcoal cards, dampening retinal fatigue over 8-hour continuous shifts.
- **Calibrated Precision:** Monospace operational telemetry (session IDs, frame rates, audit chains, latency markers) is presented cleanly alongside human-readable UI elements.
- **Quiet Authority:** Biometric verification states communicate absolute clarity without alarming or dazzling the operator. Transitions between idle, scanning, challenging, and completion states use smooth, gentle glows rather than abrupt flashing cues.

## Colors

The system uses a dark palette engineered around optical comfort and functional clarity:

- **Canvas & Surface Architecture:** Base canvas foundation sits at `#090D16`. Container layers step up to `#0B0F19` for recessed sections, `#111827` for standard cards/panels, and `#161F30` for top-level interactive surfaces and hover states. The camera preview viewport uses `#030712` to provide an absolute contrast well for incoming sensor streams.
- **Borders & Dividers:** Hairlines are strictly enforced using `#1F293D` for quiet component boundaries and `#2D3748` for focused or active structural partitions.
- **Brand & Signal Accents:** Cobalt blue (`#3B82F6`) provides active focus, selection states, and affirmative system actions, supported by lighter cobalt (`#60A5FA`) for hover states and subtle rim glows (`rgba(59, 130, 246, 0.22)`).
- **Institutional Status System:** Rather than harsh opaque pills, status tokens utilize low-luminance alpha tints with calibrated pastel text:
  - *Success / Verified / Intact:* `#34D399` text over `rgba(16, 185, 129, 0.12)` with `rgba(16, 185, 129, 0.28)` hairline.
  - *Danger / Attack Detected / Locked:* `#F87171` text over `rgba(244, 63, 94, 0.12)` with `rgba(244, 63, 94, 0.28)` hairline.
  - *Warning / Challenge Open / Evaluating:* `#FBBF24` text over `rgba(245, 158, 11, 0.12)` with `rgba(245, 158, 11, 0.28)` hairline.
  - *Informational / Session Telemetry:* `#38BDF8` text over `rgba(56, 189, 248, 0.12)` with `rgba(56, 189, 248, 0.28)` hairline.
- **Typographic Luminance:** Headlines and high-priority states use `#F1F5F9` (slate-100), secondary context uses `#94A3B8` (slate-400), and timestamps, hints, and borders use `#64748B` (slate-500).

## Typography

The typographical hierarchy utilizes **Inter** for conversational layout, navigational hierarchies, and instructional directives, paired with **JetBrains Mono** for cryptographically verified strings, session nonces, frame rates, and camera HUD metadata.

- **Legibility in the Dark:** Weight distribution avoids ultra-thin weights (under 400), which degrade under dark mode rasterization. Body text rests at 400 and headers at 600.
- **Monospace Discipline:** `telemetry-*` tiers are exclusively reserved for cryptographic hashes, block IDs, frame rate metrics (`30 FPS`), exposure lux (`LUX OPTIMAL`), session identifiers, and code-like payload diffs.
- **Section Eyebrows:** Eyebrow labels use `label-caps` in uppercase format with wide letter spacing (`0.06em`) to anchor attention above larger headers.

## Layout & Spacing

The console operates on a fixed-cap, fluid-interior grid tailored for dense monitoring interfaces:

- **Maximum Page Canvas:** Contained at `1440px` with horizontal centering, preserving scanning ergonomics without stretching telemetry elements across ultra-wide monitors.
- **Biometric Two-Column Layout:** During capture workflows, the layout uses an asymmetrical split: the camera viewfinder claims the flexible primary track (`1fr`, maintaining a clean 4:3 or 16:9 aspect constraint), beside a fixed `360px` operational inspection rail (holding step progress, user directives, hardware health, and audit feedback).
- **Responsive Stacking:** Below `960px` (tablets and mobile handheld enrolment devices), the two-column capture matrix collapses into a unified vertical stack where the viewfinder takes 100% width, followed sequentially by the prompt card and control buttons.
- **Spacing Rhythm:** Standard spacing increments adhere to a 4px/8px modular scale. Component interiors enforce `space-md` (16px) or `space-lg` (24px) breathing room to prevent dense administrative tables and configuration forms from feeling cluttered.

## Elevation & Depth

Visual depth is conveyed through layered tonal luminescence and hairline containment, replacing heavy, muddy drop shadows with crisp micro-edges:

- **Surface Tiers:**
  - *Tier 0 (Base Canvas):* `#090D16` creates the receding infinite background.
  - *Tier 1 (Panels & Shells):* `#111827` bordered by 1px solid `#1F293D`.
  - *Tier 2 (Elevated Inputs & Dialogs):* `#161F30` bordered by 1px solid `#2D3748`.
  - *Tier 3 (Viewfinder & Video Port):* `#030712` hardened inner container with an inset shadow (`inset 0 0 24px rgba(0, 0, 0, 0.7)`).
- **Micro-Shadows:** Floating modals and interactive tooltips feature a tight, high-density shadow: `0 4px 20px -2px rgba(0, 0, 0, 0.5), 0 0 0 1px #2D3748`.
- **Soft Accent Halos:** Focused controls and active biometric detection frames project a calibrated soft luminescent aura (`box-shadow: 0 0 16px -2px rgba(59, 130, 246, 0.25)`), providing a gentle glow without blinding adjacent telemetry.
- **Glassmorphic Overlays:** Persistent viewport overlays (e.g., viewfinder state pills, floating action toasts) utilize translucent backing: `background: rgba(17, 24, 39, 0.75)` with `backdrop-filter: blur(12px)`.

## Shapes

The design uses roundedness level `2` (8px standard base radius) to reflect technical precision while softening edge fatigue:

- **Controls & Form Inputs:** Form fields, standard buttons, and input dropdowns feature an `8px` (`0.5rem`) corner radius.
- **Cards & Data Viewports:** Panel enclosures, modal dialogs, and the primary camera frame feature a `12px` (`0.75rem`) to `16px` (`1rem`) corner radius (`rounded-lg` to `rounded-xl`).
- **Telemetry Chips & Status Badges:** Fully rounded pill shapes (`9999px`) to immediately distinguish transient state indicators from actionable rectangular controls.
- **Targeting Oval & Reticle:** The biometric capture guide uses an ellipse geometry with subtle crosshair alignment tick-marks positioned at cardinal quadrants (top, bottom, left, right).

## Components

### 1. Primary, Secondary & Contextual Buttons
- **Primary Action (e.g., Start Camera, Save Policy):** Background `#3B82F6`, text `#FFFFFF`, border 1px solid rgba(255, 255, 255, 0.15). Hover state: `#2563EB` with `box-shadow: 0 0 12px rgba(59, 130, 246, 0.35)`.
- **Secondary Action (e.g., Run Active Liveness, Refresh):** Background `#161F30`, text `#F1F5F9`, border 1px solid `#2D3748`. Hover state: `#1E293B` with border `#3B82F6`.
- **Destructive Action (e.g., Stop, Abort):** Background `rgba(244, 63, 94, 0.12)`, text `#F87171`, border 1px solid `rgba(244, 63, 94, 0.3)`. Hover state: `rgba(244, 63, 94, 0.2)`.

### 2. Camera Viewfinder & HUD Reticle
- **Viewport Canvas:** Deep darkened slate `#030712` container with a fine hairline border in `#1F293D`.
- **Biometric Oval Guide:** Centered, dashed oval border (width: 2px, dash-array: `8 6`). State variants:
  - *Idle / Positioning:* Translucent slate-500 (`#64748B`) with white cardinal alignment ticks.
  - *Active Scanning:* Cobalt blue (`#3B82F6`) with continuous soft rotation/sweep animation and subtle blue glow.
  - *Challenge Prompt (Blink/Smile/Turn):* Amber (`#FBBF24`) pulse.
  - *Verification Success:* Emerald (`#34D399`) solid boundary.
  - *Attack / PAD Failure:* Rose (`#F87171`) solid boundary.
- **Viewfinder Monospace HUD:** Positioned at the bottom corners inside the preview. Font is `JetBrains Mono` (`telemetry-sm`) in `#64748B`, tracking `PAD: PASSIVE_V2`, `FPS: 30`, and `LUX: OPTIMAL`.

### 3. Telemetry Status Pills & Service Indicators
- **Component Geometry:** Pill shape with `height: 24px`, padding `0 10px`, containing an active `6px` status dot paired with `11px` uppercase text.
- **Pill Dot Animation:** For active states (`GATE ACTIVE`, `ANALYSIS IN FLIGHT`), the internal status dot carries a subtle breathing opacity keyframe (1.0 to 0.4 over 2s).

### 4. Step Guidance Cards & Progress Rail
- **Header:** Eyebrow `label-caps` in `#94A3B8`, accompanied by an active step index (`Stage 2 of 3`).
- **Progress Track:** Recessed `4px` or `6px` channel (`#1F293D`), filled with a glowing cobalt indicator (`#3B82F6`) during passive evaluation, or solid `#34D399` upon successful completion.

### 5. Administrative Data Tables
- **Header Structure:** Background `#0B0F19`, text in `label-caps` (`#64748B`), bottom hairline border in `#1F293D`.
- **Row Interaction:** Alternate zebra styling is avoided; rows sit on `#111827` and elevate to `#161F30` on hover with a smooth 150ms transition.
- **Data Rendering:** Session IDs and cryptographic hashes render in `telemetry-md` with clickable quick-copy icons. Status cells house corresponding colored status pills.

### 6. Verification Verdict Banners
- Banner component spanning the full width beneath the primary interaction zone.
- *Proceed / Success:* Subtle tinted background `rgba(16, 185, 129, 0.1)`, 1px border `rgba(16, 185, 129, 0.3)`, leading with an emerald circled checkmark, containing `#F1F5F9` title and `#34D399` operational message. Includes affirmative Proceed action button.

### 7. Cryptographic Chain & Audit Cards
- Dedicated verification cards featuring a verified chain icon, `#34D399` text, and monospace block counts (`telemetry-sm`) confirming SHA-256 seal integrity and zero tamper detection.