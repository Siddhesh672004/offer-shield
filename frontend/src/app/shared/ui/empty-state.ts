import { Component, input } from '@angular/core';

export type EmptyStateIcon = 'document' | 'inbox' | 'flag' | 'search';

/**
 * What a list or page shows when there is nothing in it yet: an icon, a heading, a sentence
 * that says what will appear here or what to do, and optional actions (`emptyStateActions`).
 */
@Component({
  selector: 'app-empty-state',
  host: { class: 'empty-state' },
  template: `
    <span class="empty-state__icon" aria-hidden="true">
      <svg viewBox="0 0 24 24" focusable="false">
        @switch (icon()) {
          @case ('inbox') {
            <path d="M4 13.5 6.2 5.6A1.5 1.5 0 0 1 7.6 4.5h8.8a1.5 1.5 0 0 1 1.4 1.1l2.2 7.9" />
            <path
              d="M4 13.5h4.5l1.2 2.25h4.6l1.2-2.25H20v4.5a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 18v-4.5Z"
            />
          }
          @case ('flag') {
            <path d="M5.5 20.5v-16" />
            <path d="M5.5 4.5h11.2l-2.2 4 2.2 4H5.5" />
          }
          @case ('search') {
            <circle cx="10.75" cy="10.75" r="6" />
            <path d="m15.25 15.25 4.5 4.5" />
          }
          @default {
            <path
              d="M14 3.5H7A1.5 1.5 0 0 0 5.5 5v14A1.5 1.5 0 0 0 7 20.5h10a1.5 1.5 0 0 0 1.5-1.5V8L14 3.5Z"
            />
            <path d="M14 3.5V8h4.5M8.75 12.5h6.5M8.75 16h4.5" />
          }
        }
      </svg>
    </span>
    @if (headingLevel() === 3) {
      <h3 class="empty-state__heading">{{ heading() }}</h3>
    } @else {
      <h2 class="empty-state__heading">{{ heading() }}</h2>
    }
    <div class="empty-state__body"><ng-content /></div>
    <div class="empty-state__actions"><ng-content select="[emptyStateActions]" /></div>
  `,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      align-items: center;
      padding: var(--space-7) var(--space-5);
      border: 1px dashed var(--color-border-strong);
      border-radius: var(--radius-lg);
      background: var(--color-surface);
      text-align: center;
    }
    .empty-state__icon {
      display: grid;
      place-items: center;
      width: 3rem;
      height: 3rem;
      margin-bottom: var(--space-4);
      border-radius: var(--radius-full);
      background: var(--color-primary-subtle);
      color: var(--color-primary);
    }
    svg {
      width: 1.5rem;
      height: 1.5rem;
      fill: none;
      stroke: currentColor;
      stroke-width: 1.6;
      stroke-linecap: round;
      stroke-linejoin: round;
    }
    .empty-state__heading {
      margin-bottom: var(--space-2);
      font-size: var(--text-md);
    }
    .empty-state__body {
      max-width: 36rem;
      color: var(--color-text-muted);
    }
    .empty-state__actions {
      display: flex;
      flex-wrap: wrap;
      justify-content: center;
      gap: var(--space-2);
      margin-top: var(--space-5);
    }
    .empty-state__actions:empty {
      display: none;
    }
  `,
})
export class EmptyState {
  readonly heading = input.required<string>();
  readonly icon = input<EmptyStateIcon>('document');
  readonly headingLevel = input<2 | 3>(2);
}
