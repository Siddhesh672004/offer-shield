import { Component, input } from '@angular/core';

export type BadgeTone = 'neutral' | 'info' | 'success' | 'warn' | 'danger';
export type BadgeIcon = 'none' | 'clock' | 'check' | 'cross';

/**
 * A small status pill ("Pending", "Approved"). The text carries the meaning; the colour and
 * the optional icon only support it. For risk bands use `app-risk-band`; for scam reports use
 * `app-report-chip`.
 */
@Component({
  selector: 'app-badge',
  host: { '[class]': '"badge badge--" + tone()' },
  template: `
    @if (icon() !== 'none') {
      <svg viewBox="0 0 16 16" aria-hidden="true" focusable="false">
        <circle cx="8" cy="8" r="6.25" />
        @switch (icon()) {
          @case ('check') {
            <path d="m5.4 8.3 1.8 1.8 3.5-3.7" />
          }
          @case ('cross') {
            <path d="m6 6 4 4M10 6l-4 4" />
          }
          @default {
            <path d="M8 4.75V8l2.1 1.4" />
          }
        }
      </svg>
    }
    <ng-content />
  `,
  styles: `
    :host {
      --badge-fg: var(--color-text-secondary);
      --badge-bg: var(--color-surface-sunken);
      --badge-border: var(--color-border-strong);
      display: inline-flex;
      align-items: center;
      gap: 0.3125rem;
      padding: 0.0625rem 0.625rem;
      border: 1px solid var(--badge-border);
      border-radius: var(--radius-full);
      background: var(--badge-bg);
      color: var(--badge-fg);
      font-size: 0.8125rem;
      font-weight: var(--weight-semibold);
      line-height: 1.6;
      white-space: nowrap;
    }
    :host(.badge--info) {
      --badge-fg: var(--color-info);
      --badge-bg: var(--color-info-subtle);
      --badge-border: var(--color-info-border);
    }
    :host(.badge--success) {
      --badge-fg: var(--color-success);
      --badge-bg: var(--color-success-subtle);
      --badge-border: var(--color-success-border);
    }
    :host(.badge--warn) {
      --badge-fg: var(--color-warn);
      --badge-bg: var(--color-warn-subtle);
      --badge-border: var(--color-warn-border);
    }
    :host(.badge--danger) {
      --badge-fg: var(--color-danger);
      --badge-bg: var(--color-danger-subtle);
      --badge-border: var(--color-danger-border);
    }
    svg {
      width: 0.9375rem;
      height: 0.9375rem;
      margin-left: -0.1875rem;
      fill: none;
      stroke: currentColor;
      stroke-width: 1.6;
      stroke-linecap: round;
      stroke-linejoin: round;
    }
  `,
})
export class Badge {
  readonly tone = input<BadgeTone>('neutral');
  readonly icon = input<BadgeIcon>('none');
}
