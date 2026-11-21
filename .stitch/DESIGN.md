# Design System: Governed Precision — 数据治理与语义本体控制台

> Stitch-compatible semantic design system & visual meta-language for the Enterprise Data Governance, Semantic Ontology Kernel & Governed AI Insight Platform.

## 1. Visual Theme & Atmosphere

**Governed Precision（受治理的精密控制台）** — A restrained, architectural, high-density data console that feels like a Swiss precision instrument paired with a clinical ledger.

- **Density (7/10 · Cockpit-Refined)**: High information throughput for tables, lineage graphs, 6D metric specs, and SQL inspection without visual clutter. Generous breathing room between structural zones (`16px–24px`), crisp compactness inside data grids (`36px` row height).
- **Variance (4/10 · Structured Asymmetric)**: Predictable navigation chrome for muscle memory, paired with asymmetric split workbenches (e.g., 65/35 ontology canvas + inspector drawer, or main canvas + collapsible `AgentDock`).
- **Motion (4/10 · Tactile Restrained)**: Crisp, purposeful mechanical feedback (`160ms–220ms` ease-out / spring physics). Never decorative or bouncy.
- **Philosophy**: Data, lineage, verification gates, and governance status are the visual heroes. Chrome stays quiet; color is rationed strictly for primary actions, semantic verification states, and sensitivity classification.

---

## 2. Color Palette & Roles

Single calibrated cool-neutral Slate/Zinc scale (no warm/cool gray drift) with **one** restrained authoritative accent (saturation < 70%). Pure black (`#000000`) and AI purple/neon gradients are strictly banned.

### Neutrals & Surfaces
- **Alabaster Canvas** (`#F7F8FA`) — Primary workspace background (`--dg-canvas`).
- **Pure Paper Surface** (`#FFFFFF`) — Primary cards, tables, editors, and modal interiors (`--dg-surface`).
- **Porcelain Muted** (`#F1F3F6`) — Nested panels, code blocks, table headers, subtle hover fills (`--dg-surface-muted`).
- **Graphite Obsidian Rail** (`#161922`) — Left primary navigation rail (`--dg-sidebar`), high-focus anchor without pure black.
- **Graphite Active Tile** (`#232838`) — Active navigation item background (`--dg-sidebar-active`).
- **Mist Rail Text** (`#A3AEBF`) — Inactive sidebar labels (`--dg-sidebar-text`).
- **Charcoal Ink** (`#181C24`) — Primary headings, table cell values, primary text (`--dg-text`).
- **Slate Secondary** (`#525B6C`) — Secondary labels, descriptions, helper copy (`--dg-text-secondary`).
- **Muted Pewter** (`#7C8698`) — Timestamps, hashes, breadcrumbs, metadata (`--dg-text-muted`).
- **Hairline Divider** (`#E5E8EE`) — 1px structural borders, table row rules, panel edges (`--dg-border`).
- **Crisp Input Border** (`#CFD6E2`) — Form controls, interactive outlines (`--dg-border-strong`).

### Single Accent (Primary Action)
- **Cobalt Trust** (`#2456B8`) — Primary buttons, active tab underlines, focus rings, selected graph nodes (`--dg-primary`).
- **Cobalt Hover** (`#1D4696`) — Primary button hover state (`--dg-primary-hover`).
- **Cobalt Wash** (`#EDF2FC`) — Selected table rows, active filter chips, subtle highlight tint (`--dg-primary-soft`).

### Semantic & Governance Statuses (Rationed Strictly for State)
- **Verified Emerald** (`#178058` / tint `#ECF8F3`) — `PUBLISHED`, `PASS`, healthy connector, verified PK/FK (`--dg-success`).
- **Cautionary Amber** (`#B86B14` / tint `#FDF6EC`) — `PROPOSED`, `STALE_REVIEW`, `LOW_CONFIDENCE`, `L3` sensitive badge (`--dg-warning`).
- **Audit Crimson** (`#C23B3B` / tint `#FDF1F1`) — `FAIL`, blocked gate (G1–G4), delete-protection refusal, `L4` critical data, destructive confirmation (`--dg-danger`).
- **Neutral Slate Info** (`#4B5565` / tint `#F2F4F7`) — `DRAFT`, `DEPRECATED`, `INCONCLUSIVE`, neutral metadata tags (`--dg-info`).

### Governance-Specific Micro-Palettes (Low-Saturation Pill Badges)
- **Provenance Badges**:
  - `db_constraint`: Emerald outline (`#178058` text on `#ECF8F3`)
  - `knowledge_graph`: Cobalt outline (`#2456B8` text on `#EDF2FC`)
  - `historical_sql`: Neutral slate (`#4B5565` text on `#F2F4F7`)
  - `llm_inference`: Amber outline (`#B86B14` text on `#FDF6EC`) + mandatory human-review lock icon
- **Sensitivity Tiers (`L1–L4`)**: `L1` Slate (`#64748B`), `L2` Cobalt (`#2456B8`), `L3` Amber (`#B86B14`), `L4` Crimson (`#C23B3B`).

---

## 3. Typography Rules

Sans-serif + Monospace pairing exclusively. Serif fonts and `Inter` are banned in this console.

- **Display & UI Sans**: `Geist`, `"PingFang SC"`, `"Noto Sans SC"`, `-apple-system`, sans-serif.
  - **Page Title**: `20px / 600`, tight tracking (`-0.02em`), line-height `1.3`, Charcoal Ink (`#181C24`).
  - **Section Heading**: `15px / 600`, tracking `-0.01em`, line-height `1.4`.
  - **Body & Table Cell**: `13px–14px / 400`, relaxed leading (`1.55`), max `65ch` for prose descriptions.
  - **Caption & Metadata**: `12px / 500`, Muted Pewter (`#7C8698`).
- **Monospace (Mandatory for Precision Data)**: `Geist Mono`, `"JetBrains Mono"`, monospace.
  - Used for SQL expressions, YAML projections, canonical Object IDs (`sales.metric.return_rate`), SHA-256 `content_digest` hashes, timestamps, and **all numeric table columns & KPI values** (`font-variant-numeric: tabular-nums`).

---

## 4. Layout & App Shell Architecture

Grid-first responsive architecture (`min-h-[100dvh]`, zero flexbox percentage hacks):

1. **Left Navigation Rail (`220px` fixed, collapsible to `64px`)**: Graphite Obsidian (`#161922`). Top brand mark + grouped domain navigation (Workbench, Metadata & Ontology, Data Integration, Governance, AI Insight, Settings). Active state uses Graphite Active Tile (`#232838`) with a `3px` left Cobalt Trust indicator bar.
2. **Top Utility Header (`56px` sticky)**: Pure Paper Surface (`#FFFFFF`) with `1px solid #E5E8EE` bottom hairline. Left: multi-level Breadcrumb path. Right: **Workspace Tenant Switcher** (clearly bounded pill), Governance Inbox indicator, User menu, and `AgentDock` toggle.
3. **Main Canvas (`.page-container`, `16px` padding, `16px` vertical rhythm)**:
   - Standard Page Skeleton: **Compact Page Header** (title + status pill + max 1 primary CTA) → **Filter/Summary Strip** (horizontal hairline-divided strip, NOT 3 generic equal hero cards) → **Primary Asymmetric Split or High-Density Data Grid** → **Slide-over Inspector Drawer**.
4. **Collapsible Right `AgentDock` (`380px` width)**: Docked on the right edge with `1px solid #E5E8EE` left divider. Houses multi-turn AI Insight chat, semantic subgraph grounding pills, and tool call traces without obscuring the primary table or ontology graph.

---

## 5. Component & Interaction Stylings

- **Corner Roundness**: `ROUND_EIGHT` (`8px` for cards/drawers/modals, `6px` for buttons/inputs, `4px` for micro-tags/code chips, `9999px` only for status dots).
- **Elevation**: Flat architectural surfaces separated by `1px solid #E5E8EE` hairlines rather than heavy shadows. Elevated overlays (dropdowns, drawers, modals) use a single soft, cool-tinted whisper shadow (`0 8px 24px rgba(24, 28, 36, 0.08)`).
- **Buttons**:
  - Primary: Cobalt Trust (`#2456B8`) fill, white text, flat with zero outer glow, subtle `-1px` tactile press feedback on `:active`. Maximum one primary CTA per view/toolbar.
  - Secondary: Pure Paper Surface (`#FFFFFF`) with `1px solid #CFD6E2` border and Charcoal Ink text.
  - Ghost / Destructive: Transparent fill; destructive actions require confirmation dialog.
- **High-Density Data Tables**: Header in Porcelain Muted (`#F1F3F6`) with `12px / 600` Slate Secondary labels; body rows `36px–40px` height with `1px solid #E5E8EE` bottom rules, zero vertical zebra striping, hover tint `#F7F8FA`, numeric columns right-aligned in `Geist Mono` with `tabular-nums`.
- **Forms & Inputs**: Label positioned cleanly above input (`12px / 500`), helper text optional, inline error text in Audit Crimson (`#C23B3B`) directly below. Focus ring: `2px` Cobalt Wash (`#EDF2FC`) + `1px` Cobalt Trust border. No floating labels.
- **5-Tier Overlay Hierarchy**:
  1. **Toast**: Non-blocking top-right notification (`3s` auto-dismiss).
  2. **Confirm Dialog**: Standardized destructive/state-change confirmation (never raw browser/untyped prompts).
  3. **Short Modal (`480px–560px`)**: Quick creation or YAML import/export preview.
  4. **Slide-Over Drawer (`520px–720px`)**: Deep object inspection (6D Metric Spec, Lineage Impact, Verification Report) preserving background context.
  5. **Host Consent View (`HostConsent`, Constitution C7)**: Isolated, tamper-proof approval modal rendered strictly by host chrome with a distinct `2px` Cautionary Amber (`#B86B14`) or Audit Crimson (`#C23B3B`) top accent stripe, explicit parameter diff table, sensitivity/provenance summary, and irreversible action acknowledgement.
- **Loading & Empty States**: Skeletal shimmer placeholders matching exact table/card geometry (no generic circular spinners). Empty states use clean monochrome architectural line illustrations + 1 clear action prompt.

---

## 6. Motion & Micro-Interaction Philosophy

- **Timing & Physics**: Interactive transitions animate exclusively via `transform` and `opacity`. Panel/drawer reveals use crisp ease-out (`200ms cubic-bezier(0.16, 1, 0.3, 1)`). Hover/press states respond in `120ms`.
- **Functional Micro-Motion**: Subtle pulsing emerald/amber status dot (`2s` gentle opacity loop) exclusively for live running verification probes or streaming agent responses. Staggered `30ms` row/panel fade-in on initial view load only.

---

## 7. Anti-Patterns (Strictly Banned)

- **No generic AI purple/cyan neon gradients**, glowing buttons, or glassmorphism blur over data tables.
- **No pure black (`#000000`)** and no raw Element Plus default `#409eff` literals in views.
- **No `Inter` font** and no serif fonts anywhere in the console.
- **No emojis** in navigation, status badges, table headers, or buttons (use crisp `16px` 1.5px-stroke monochrome icons).
- **No 3-column equal marketing KPI cards** with fabricated numbers (`99.99% uptime`, `124ms avg`). Display real governance counts or explicit `[metric]` placeholders.
- **No circular loading spinners** where skeletal geometry fits.
- **No in-chat high-risk approvals**: All publishing, sensitive grants, and write actions must elevate to the Host Consent modal.
