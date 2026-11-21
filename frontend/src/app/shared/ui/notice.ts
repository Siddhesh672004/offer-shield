import { Component, input } from '@angular/core';

export type NoticeTone = 'info' | 'success' | 'warn' | 'danger';

/**
 * A short message box with an optional bold heading. The tone is shown by colour and by shape
 * (circle, check, triangle, octagon), never by colour alone. It is not a live region: pages
 * announce changes through the shell's single status region (Announcer).
 */
@Component({
  selector: 'app-notice',
  host: { '[class]': '"notice notice--" + tone()' },
  template: `
    <svg class="notice__icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      @switch (tone()) {
        @case ('success') {
          <circle cx="12" cy="12" r="9" />
          <path d="m8 12.5 2.8 2.8L16.5 9" />
        }
        @case ('warn') {
          <path d="M12 3.5 2.5 20h19L12 3.5Z" />
          <path d="M12 10v4.5M12 17.5v.01" />
        }
        @case ('danger') {
          <path d="M8.2 3h7.6L21 8.2v7.6L15.8 21H8.2L3 15.8V8.2Z" />
          <path d="M12 7.5v5.5M12 16.5v.01" />
        }
        @default {
          <circle cx="12" cy="12" r="9" />
          <path d="M12 11v5.5M12 7.5v.01" />
        }
      }
    </svg>
    <div class="notice__body">
      @if (heading(); as heading) {
        <p class="notice__heading">{{ heading }}</p>
      }
      <ng-content />
    </div>
  `,
  styles: `
    :host {
      --notice-accent: var(--color-info);
      --notice-bg: var(--color-info-subtle);
      --notice-border: var(--color-info-border);
      display: flex;
      gap: var(--space-3);
      align-items: flex-start;
      margin: 0 0 var(--space-5);
      padding: var(--space-3) var(--space-4);
      border: 1px solid var(--notice-border);
      border-radius: var(--radius-md);
      background: var(--notice-bg);
      color: var(--color-text);
    }
    :host(.notice--success) {
      --notice-accent: var(--color-success);
      --notice-bg: var(--color-success-subtle);
      --notice-border: var(--color-success-border);
    }
    :host(.notice--warn) {
      --notice-accent: var(--color-warn);
      --notice-bg: var(--color-warn-subtle);
      --notice-border: var(--color-warn-border);
    }
    :host(.notice--danger) {
      --notice-accent: var(--color-danger);
      --notice-bg: var(--color-danger-subtle);
      --notice-border: var(--color-danger-border);
    }
    .notice__icon {
      width: 1.25rem;
      height: 1.25rem;
      margin-top: 0.125rem;
      fill: none;
      stroke: var(--notice-accent);
      stroke-width: 2;
      stroke-linecap: round;
      stroke-linejoin: round;
    }
    .notice__body {
      min-width: 0;
      overflow-wrap: anywhere;
    }
    .notice__heading {
      margin: 0 0 var(--space-1);
      color: var(--notice-accent);
      font-weight: var(--weight-semibold);
    }
  `,
})
export class Notice {
  readonly tone = input<NoticeTone>('info');
  readonly heading = input<string | null>(null);
}
