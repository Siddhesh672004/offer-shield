import { Component, input } from '@angular/core';

export type NoticeTone = 'info' | 'ok' | 'warn' | 'danger';

/**
 * A short message box. The tone is shown by colour and by shape (circle, check, triangle,
 * octagon), never by colour alone. It is not a live region: pages announce changes through
 * the shell's single status region (Announcer).
 */
@Component({
  selector: 'app-notice',
  host: { '[class]': '"notice notice--" + tone()' },
  template: `
    <svg class="notice__icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      @switch (tone()) {
        @case ('ok') {
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
    <div class="notice__body"><ng-content /></div>
  `,
  styles: `
    :host {
      display: flex;
      gap: var(--space-3);
      align-items: flex-start;
      margin: 0 0 var(--space-5);
      padding: var(--space-3) var(--space-4);
      border: 1px solid currentColor;
      border-left-width: 4px;
      border-radius: var(--radius-sm);
      background: var(--color-accent-wash);
      color: var(--color-accent-strong);
    }
    :host(.notice--ok) {
      background: var(--color-ok-wash);
      color: var(--color-ok);
    }
    :host(.notice--warn) {
      background: var(--color-warn-wash);
      color: var(--color-warn);
    }
    :host(.notice--danger) {
      background: var(--color-danger-wash);
      color: var(--color-danger);
    }
    .notice__icon {
      flex: none;
      width: 1.25rem;
      height: 1.25rem;
      margin-top: 0.15rem;
      fill: none;
      stroke: currentColor;
      stroke-width: 2;
      stroke-linecap: round;
      stroke-linejoin: round;
    }
    .notice__body {
      min-width: 0;
      color: var(--color-ink);
      overflow-wrap: anywhere;
    }
  `,
})
export class Notice {
  readonly tone = input<NoticeTone>('info');
}
