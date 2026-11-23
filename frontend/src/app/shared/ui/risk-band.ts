import { Component, computed, input } from '@angular/core';

export type RiskBand = 'LOW' | 'MEDIUM' | 'HIGH';

/** PRD §7.2 band messages. Show them verbatim; never reword them. */
export const RISK_BAND_MESSAGES: Readonly<Record<RiskBand, string>> = {
  LOW: 'No major red flags found. This does not prove the offer is genuine.',
  MEDIUM: "Warning signs. Verify through the company's official website or careers page.",
  HIGH: 'Strong scam indicators. Do not pay anything.',
};

export const RISK_BAND_LABELS: Readonly<Record<RiskBand, string>> = {
  LOW: 'Low risk',
  MEDIUM: 'Medium risk',
  HIGH: 'High risk',
};

const SHAPES: Readonly<Record<RiskBand, string>> = {
  LOW: 'circle',
  MEDIUM: 'triangle',
  HIGH: 'hexagon',
};

/**
 * A risk band shown by word, shape and colour together (Low ○, Medium △, High ⬣), so it
 * never relies on colour alone. `sm` is an inline pill for lists and tables; `lg` is the
 * report header with the PRD §7.2 message and, optionally, the 0–100 score.
 */
@Component({
  selector: 'app-risk-band',
  host: {
    '[class]': '"risk-band risk-band--" + band().toLowerCase() + " risk-band--" + size()',
  },
  template: `
    <svg
      class="risk-band__shape"
      viewBox="0 0 24 24"
      aria-hidden="true"
      focusable="false"
      [attr.data-shape]="shape()"
    >
      @switch (band()) {
        @case ('LOW') {
          <circle cx="12" cy="12" r="7.5" />
        }
        @case ('MEDIUM') {
          <path d="M12 4.25 20.25 19H3.75L12 4.25Z" />
        }
        @default {
          <path d="M3 12 7.5 4.2h9L21 12l-4.5 7.8h-9L3 12Z" />
        }
      }
    </svg>
    @if (size() === 'lg') {
      <div class="risk-band__text">
        <p class="risk-band__label">{{ label() }}</p>
        <p class="risk-band__message">{{ message() }}</p>
      </div>
      @if (score() !== null) {
        <p class="risk-band__score">
          <span class="risk-band__score-label">Score</span>
          <span class="risk-band__score-value"
            >{{ score() }}<span class="risk-band__score-max"> / 100</span></span
          >
        </p>
      }
    } @else {
      <span class="risk-band__label">{{ label() }}</span>
    }
  `,
  styles: `
    :host {
      --rb-text: var(--risk-low);
      --rb-shape: var(--risk-low-shape);
      --rb-subtle: var(--risk-low-subtle);
      --rb-border: var(--risk-low-border);
      color: var(--rb-text);
    }
    :host(.risk-band--medium) {
      --rb-text: var(--risk-medium);
      --rb-shape: var(--risk-medium-shape);
      --rb-subtle: var(--risk-medium-subtle);
      --rb-border: var(--risk-medium-border);
    }
    :host(.risk-band--high) {
      --rb-text: var(--risk-high);
      --rb-shape: var(--risk-high-shape);
      --rb-subtle: var(--risk-high-subtle);
      --rb-border: var(--risk-high-border);
    }
    .risk-band__shape {
      fill: none;
      stroke: var(--rb-shape);
      stroke-width: 2.5;
      stroke-linejoin: round;
    }
    /* High is the only solid shape; a thin mitred stroke keeps its corners crisp. */
    :host(.risk-band--high) .risk-band__shape {
      fill: var(--rb-shape);
      stroke-width: 1;
      stroke-linejoin: miter;
    }

    /* Small: an inline pill */
    :host(.risk-band--sm) {
      display: inline-flex;
      align-items: center;
      gap: 0.375rem;
      padding: 0.125rem 0.625rem 0.125rem 0.5rem;
      border: 1px solid var(--rb-border);
      border-radius: var(--radius-full);
      background: var(--rb-subtle);
      font-size: var(--text-sm);
      font-weight: var(--weight-semibold);
      line-height: 1.5;
      white-space: nowrap;
    }
    :host(.risk-band--sm) .risk-band__shape {
      width: 1rem;
      height: 1rem;
    }

    /* Large: the report header */
    :host(.risk-band--lg) {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr) auto;
      align-items: start;
      gap: var(--space-3) var(--space-4);
      padding: var(--space-4) var(--space-5);
      border: 1px solid var(--rb-border);
      border-radius: var(--radius-lg);
      background: var(--rb-subtle);
    }
    :host(.risk-band--lg) .risk-band__shape {
      width: 2.25rem;
      height: 2.25rem;
    }
    :host(.risk-band--lg) .risk-band__label {
      margin: 0;
      font-size: var(--text-lg);
      font-weight: var(--weight-bold);
      letter-spacing: -0.01em;
      line-height: var(--leading-tight);
    }
    .risk-band__message {
      margin: var(--space-1) 0 0;
      color: var(--color-text);
      font-weight: var(--weight-medium);
    }
    .risk-band__score {
      display: grid;
      justify-items: end;
      margin: 0;
      color: var(--color-text);
      line-height: 1.1;
    }
    .risk-band__score-label {
      color: var(--color-text-muted);
      font-size: var(--text-sm);
    }
    .risk-band__score-value {
      color: var(--rb-text);
      white-space: nowrap;
      font-size: var(--text-xl);
      font-weight: var(--weight-bold);
      font-variant-numeric: tabular-nums;
    }
    .risk-band__score-max {
      color: var(--color-text-muted);
      font-size: var(--text-sm);
      font-weight: var(--weight-medium);
    }
    @media (max-width: 30rem) {
      :host(.risk-band--lg) {
        grid-template-columns: auto minmax(0, 1fr);
        padding: var(--space-4);
      }
      .risk-band__score {
        grid-column: 2;
        justify-items: start;
      }
    }
  `,
})
export class RiskBandIndicator {
  readonly band = input.required<RiskBand>();
  readonly size = input<'sm' | 'lg'>('sm');
  /** 0–100, shown only in the large variant. */
  readonly score = input<number | null>(null);

  protected readonly label = computed(() => RISK_BAND_LABELS[this.band()]);
  protected readonly message = computed(() => RISK_BAND_MESSAGES[this.band()]);
  protected readonly shape = computed(() => SHAPES[this.band()]);
}
