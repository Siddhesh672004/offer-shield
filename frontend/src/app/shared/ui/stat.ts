import { Component, input } from '@angular/core';

/**
 * One number with its label, for dashboard rows (admin and TPO counts). Put several in a
 * `.stat-grid` (or any grid). For label/value pairs in running text use `<dl class="kv">`.
 */
@Component({
  selector: 'app-stat',
  host: { class: 'stat' },
  template: `
    <span class="stat__label">{{ label() }}</span>
    <span class="stat__value">{{ value() }}</span>
    @if (hint(); as hint) {
      <span class="stat__hint">{{ hint }}</span>
    }
  `,
  styles: `
    :host {
      display: grid;
      gap: var(--space-1);
      padding: var(--space-4) var(--space-5);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-lg);
      background: var(--color-surface);
    }
    .stat__label {
      color: var(--color-text-muted);
      font-size: var(--text-sm);
      font-weight: var(--weight-medium);
    }
    .stat__value {
      color: var(--color-text);
      font-size: var(--text-xl);
      font-weight: var(--weight-bold);
      font-variant-numeric: tabular-nums;
      letter-spacing: var(--tracking-tight);
      line-height: var(--leading-tight);
    }
    .stat__hint {
      color: var(--color-text-muted);
      font-size: var(--text-sm);
    }
  `,
})
export class Stat {
  readonly label = input.required<string>();
  readonly value = input.required<string | number>();
  readonly hint = input<string | null>(null);
}
