# Shared UI (for P12 and later)

A small design system: tokens, a few global CSS primitives, and standalone components.
Build new screens from these before writing page-specific CSS.

## Foundations

- **Tokens:** `src/styles/tokens.css`.
  - Use the semantic tokens: `--color-*`, `--risk-*`, `--space-*`, `--radius-*`, `--shadow-*`, `--text-*`.
  - The raw scales (`--gray-*`, `--blue-*`, …) only define them.
  - Every listed text pairing passes WCAG AA.
- **Type:** Public Sans Variable, bundled from npm (`@fontsource-variable/public-sans`).
  - It is served from `'self'`, so the CSP `font-src 'self'` still holds and no external request is made.
  - Use sentence case, and `font-variant-numeric: tabular-nums` (`.tabular`) for numbers in columns.
- **Layout:** the shell gives every page a centred column (`--container-max`, `--gutter`).
  - For two columns, use a grid with `minmax(0, 1fr) 18–20rem` from `60rem` up; stack below that.
  - It must work at 360 px with no horizontal scroll.
- **Targets and focus:** interactive things are at least 44 px tall (`--touch`).
  - The global `:focus-visible` ring applies everywhere; never remove it from interactive elements.
- **Motion:** only to show a change (press feedback, the account menu opening). Keep it under 200 ms.
  - Use `--ease-out` and animate transform/opacity only.
  - Wrap it in `@media (prefers-reduced-motion: no-preference)`.

## Global CSS primitives (`src/styles.css`)

| Class                                                                               | Use                                                                                                               |
| ----------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| `.button` + `--secondary` / `--ghost` / `--danger`, `--sm` / `--lg`, `--block`      | Prefer the `appButton` component (below).                                                                         |
| `.input`, `.select`, `.textarea` (+ `.input--short`)                                | Text controls. They are 16 px, so phones don't zoom. Set `aria-invalid="true"` for the red border.                |
| `.field`, `.field__label`, `.field__hint`, `.field__error`                          | Field layout; `app-field` renders these for you.                                                                  |
| `.card` (+ `.card__header`, `__title`, `__description`, `__footer`, `.card--flush`) | The one container style: white, 1 px border, 12 px radius.                                                        |
| `.table-wrap` > `.table` (`th`, `td`, `.num`)                                       | Data tables. The wrapper scrolls sideways on phones. Give it `role="region"`, an `aria-label` and `tabindex="0"`. |
| `.list` > `li` (+ `.list__row`)                                                     | Divided rows: queues and history.                                                                                 |
| `.kv` (`<dl class="kv"><div><dt/><dd/></div></dl>`)                                 | Label/value details. It stacks on phones.                                                                         |
| `.stat-grid`                                                                        | A responsive row of `app-stat` tiles.                                                                             |
| `.stack`, `.cluster`                                                                | Vertical spacing, and a wrapping row of chips or buttons.                                                         |
| `.lead`, `.fine-print`, `.muted`, `.tabular`, `.link-action`, `.visually-hidden`    | Text helpers. `.link-action` is a 44 px stand-alone link.                                                         |
| `.plain-text`, `.nowrap`                                                            | Text from the API or a person (keeps line breaks, wraps long words); keep a date or amount on one line.           |
| `.steps` (`<ol class="steps" role="list">` > `li` > `strong` + `p`)                 | Numbered "how it works" steps in a side panel.                                                                    |

## Components

| Component              | Selector                            | Notes                                                                                                                                                                                                                                                                                 |
| ---------------------- | ----------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `Button`               | `button[appButton]`, `a[appButton]` | Inputs: `variant` (`primary`, `secondary`, `ghost`, `danger`), `size` (`sm`, `md`, `lg`), `block`, `loading`. `loading` adds a decorative spinner and `aria-busy`. Set `[disabled]` yourself.                                                                                         |
| `PageHeader`           | `app-page-header`                   | `heading` renders the page's only `<h1>`; the shell focuses it after navigation. Project the description as content; put actions on elements marked `pageHeaderActions`.                                                                                                              |
| `Field`                | `app-field`                         | Inputs: `label`, `fieldId` (the control's id), `hint`, `error`. Hint and error ids are `<fieldId>-hint` and `<fieldId>-error`; use `fieldDescribedBy()` for `aria-describedby`.                                                                                                       |
| `Notice`               | `app-notice`                        | `tone`: `info`, `success`, `warn` or `danger`; optional `heading`. Each tone has its own shape. It is not a live region: announce with `Announcer`.                                                                                                                                   |
| `Badge`                | `app-badge`                         | Status pill. `tone`: `neutral`, `info`, `success`, `warn` or `danger`; `icon`: `clock`, `check` or `cross`. The text carries the meaning.                                                                                                                                             |
| `RiskBandIndicator`    | `app-risk-band`                     | `band` (`LOW`, `MEDIUM`, `HIGH`) is shown as word + shape + colour: Low ○, Medium △, High ⬣. `size="sm"` is a pill for lists. `size="lg"` is the report header, with the PRD §7.2 message verbatim and an optional `score`. `RISK_BAND_MESSAGES` and `RISK_BAND_LABELS` are exported. |
| `ReportChip`           | `app-report-chip`                   | `status="reported"` + `reporters` shows a quiet dashed chip: "Reported as a scam by N users (not verified)". `status="confirmed"` shows a solid red "Confirmed scam" chip. Never use one look for the other.                                                                          |
| `SourceKindTag`        | `app-source-kind`                   | Shows "Source N: …" for PRD §7 kinds 1–5. Set `aiExtracted` to add the "AI-extracted" tag. Every signal shows one.                                                                                                                                                                    |
| `EmptyState`           | `app-empty-state`                   | Inputs: `heading`, `icon` (`document`, `inbox`, `flag`, `search`), `headingLevel` (2 or 3). Project a sentence saying what will appear here; actions go on elements marked `emptyStateActions`.                                                                                       |
| `Spinner` / `Skeleton` | `app-spinner`, `app-skeleton`       | Spinner has an optional visible `label`. Skeleton has `lines` and is hidden from assistive technology.                                                                                                                                                                                |
| `Stat`                 | `app-stat`                          | Inputs: `label`, `value`, optional `hint`. Use it for admin and TPO counts.                                                                                                                                                                                                           |
| `AiTag`                | `app-ai-tag`                        | "AI-extracted" by default, or the projected text ("AI-written summary"). Put it next to anything the AI produced.                                                                                                                                                                     |
| `ConfirmStep`          | `app-confirm-step`                  | Inline "are you sure?" for a destructive action (reject, revert, delete): `question`, `confirmLabel`, outputs `confirmed` / `cancelled`. The red confirm button takes focus and is described by the question; Escape cancels. Put focus back on the trigger after `cancelled`.        |

## Report building blocks (`shared/report`, `shared/format`)

- **`report-models.ts`**: `ReportSignal` is the signal shape of both `GET /api/reports/{id}` and the TPO/admin `evidence.signals` (D-04). Also `CheckOutcome`, `overrideTitles()` and `checkTitles()`.
- **`SignalList`** (`<app-signal-list [signals]="…" />`): red flags first, then the checks that couldn't run, then the others folded in a `<details>`. Its group headings are `h3`, so put it under an `h2`.
  - Each row is a **`SignalItem`** (`li[appSignalItem]`): outcome word + icon, its effect ("Sets High", "+20 points", "Not counted"), the explanation, the evidence as plain text, and `app-source-kind` with "AI-extracted".
- **`Evidence`** (`<app-evidence [evidence]="…" />`, types in `evidence-models.ts`): the D-04 evidence a TPO or the admin sees. It shows the band (`lg`), "Why High", a partial notice, the sender domain, the registry markers and `<app-signal-list>`. Markers use `app-report-chip` (confirmed vs reported, never mixed); a REJECTED marker shows "Not counted". Phone and UPI markers show the API's masked `hint` ("••••1234") and "typed by the student" or "in the letter" (`source`); both are optional. With `[revertable]="true"` each confirmed marker gets Revert behind a `ConfirmStep` and emits `(revert)`; `revertingId` disables them while one is sent. Its headings are h3.
- **`PdfLinkButton`** (`<app-pdf-link [available]="…" [fetch]="fn" />`): the D-05 PDF link in two steps, so no popup blocker interferes. "Get the PDF link" asks the server, then "Open the PDF" opens a new tab with `rel="noopener noreferrer"` and shows the expiry. A 404 `PDF_NOT_AVAILABLE` shows "No PDF stored for this letter". Pass a stable function: it is an input.
- **`format/en-in.ts`**: `formatDate`, `formatDateTime`, `formatRupees`, `formatNumber`, `formatWait` (a `Retry-After` in words), and the pipes `appDate`, `appDateTime`, `appRupees`. They use `Intl` with `en-IN`, so no locale data is bundled.
- **`core/http/problem.ts`** also has `problemViolations()` (the fields of a 400 `VALIDATION_FAILED`) and `retryAfterSeconds()` (a 429's wait).
- **`core/http/if-match.ts`**: `ifMatch(version)` sends `If-Match: "<version>"` with a decision (a stale version answers 409, a missing header 428).
- **`core/a11y/focus.ts`**: `focusAfterRender(host, injector, ...selectors)` focuses the first match after the next render (the next row's heading, else the empty state, else a notice). It adds `tabindex="-1"` where needed.
- **`shared/forms/form-errors.ts`**: `filledIn()` (required, not only spaces) and `firstError()` for Signal Forms.

## Testing pages that use resources

`rxResource` and `httpResource` keep the app "unstable" until they load, so `fixture.whenStable()` waits for an answer the test hasn't sent yet. Let Angular render first with `await new Promise((r) => setTimeout(r))` (twice), answer the request with `HttpTestingController`, then `await fixture.whenStable()`. See `features/student/my-submissions.spec.ts`.

The shell's account menu (`core/layout/account-menu.ts`) is a native `popover` and needs no JS for open, close or Escape.

## Copy rules (CLAUDE.md, enforced by specs)

- Show the band messages verbatim, from `RISK_BAND_MESSAGES`.
- Never write "verified", "safe", "legit" or "guaranteed" in our own copy. The only allowed use is the mandated "(not verified)" in `app-report-chip`.
- Unverified reports never look like facts. Label AI-derived fields "AI-extracted".
- Use only fake data in tests and examples.
