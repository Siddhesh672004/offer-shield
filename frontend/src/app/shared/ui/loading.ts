import { Component, computed, input } from '@angular/core';

/**
 * A spinner with an optional visible label ("Loading your join request…"). It is decorative
 * and not a live region: announce the result through the Announcer when it matters.
 */
@Component({
  selector: 'app-spinner',
  host: { class: 'spinner' },
  template: `
    <span class="spinner__ring" aria-hidden="true"></span>
    @if (label(); as label) {
      <span class="spinner__label">{{ label }}</span>
    }
  `,
  styles: `
    :host {
      display: inline-flex;
      align-items: center;
      gap: var(--space-3);
      min-height: var(--touch);
      color: var(--color-text-muted);
    }
    .spinner__ring {
      width: 1.25rem;
      height: 1.25rem;
      border: 2px solid var(--color-border-strong);
      border-top-color: var(--color-primary);
      border-radius: 50%;
      animation: spin 700ms linear infinite;
    }
    @media (prefers-reduced-motion: reduce) {
      .spinner__ring {
        animation-duration: 1.6s;
      }
    }
  `,
})
export class Spinner {
  readonly label = input<string | null>(null);
}

/**
 * Grey placeholder lines that hold a layout's shape while data loads. Hidden from assistive
 * technology; pair it with visible or announced text.
 */
@Component({
  selector: 'app-skeleton',
  host: { class: 'skeleton', 'aria-hidden': 'true' },
  template: `
    @for (width of widths(); track $index) {
      <span class="skeleton__line" [style.width]="width"></span>
    }
  `,
  styles: `
    :host {
      display: grid;
      gap: var(--space-3);
    }
    .skeleton__line {
      display: block;
      height: 0.875rem;
      border-radius: var(--radius-xs);
      background: var(--gray-200);
    }
    /* A gentle opacity pulse; static when the user asks for reduced motion. */
    @media (prefers-reduced-motion: no-preference) {
      .skeleton__line {
        animation: pulse 1.6s ease-in-out infinite;
      }
    }
    @keyframes pulse {
      50% {
        opacity: 0.5;
      }
    }
  `,
})
export class Skeleton {
  readonly lines = input(3);

  /** Varied widths read as text; the last line is shorter. */
  protected readonly widths = computed(() => {
    const count = Math.max(1, this.lines());
    return Array.from({ length: count }, (_, index) =>
      index === count - 1 && count > 1 ? '60%' : index % 2 === 0 ? '100%' : '92%',
    );
  });
}
