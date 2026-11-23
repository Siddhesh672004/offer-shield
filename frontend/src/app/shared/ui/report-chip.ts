import { Component, computed, input } from '@angular/core';

export type ReportStatus = 'reported' | 'confirmed';

/**
 * How other people's scam reports appear (CLAUDE.md copy rules). The two looks must never be
 * confused:
 * - `reported`: a quiet, dashed, neutral chip, "Reported as a scam by N users (not verified)".
 *   Unverified reports are never shown as fact.
 * - `confirmed`: a solid red chip with an octagon, "Confirmed scam" (an admin or TPO confirmed it).
 */
@Component({
  selector: 'app-report-chip',
  host: { '[class]': '"report-chip report-chip--" + status()' },
  template: `
    @if (status() === 'confirmed') {
      <svg viewBox="0 0 16 16" aria-hidden="true" focusable="false" data-shape="octagon">
        <path class="report-chip__fill" d="M5.3 1.5h5.4l3.8 3.8v5.4l-3.8 3.8H5.3l-3.8-3.8V5.3Z" />
        <path class="report-chip__mark" d="M8 4.75v4M8 11.25v.01" />
      </svg>
    } @else {
      <svg viewBox="0 0 16 16" aria-hidden="true" focusable="false" data-shape="dashed-circle">
        <circle class="report-chip__ring" cx="8" cy="8" r="5.75" />
      </svg>
    }
    <span>{{ text() }}</span>
  `,
  styles: `
    :host {
      display: inline-flex;
      align-items: center;
      gap: 0.375rem;
      max-width: 100%;
      padding: 0.1875rem 0.625rem 0.1875rem 0.5rem;
      border-radius: 1rem; /* a pill on one line, a soft box when it wraps */
      font-size: var(--text-sm);
      line-height: 1.4;
    }
    svg {
      width: 1rem;
      height: 1rem;
    }
    :host(.report-chip--reported) {
      border: 1px dashed var(--gray-400);
      background: var(--color-surface-muted);
      color: var(--color-text-secondary);
      font-weight: var(--weight-medium);
    }
    .report-chip__ring {
      fill: none;
      stroke: var(--gray-500);
      stroke-width: 1.5;
      stroke-dasharray: 2.4 2.1;
    }
    :host(.report-chip--confirmed) {
      border: 1px solid var(--red-700);
      background: var(--red-700);
      color: var(--color-text-inverse);
      font-weight: var(--weight-bold);
    }
    .report-chip__fill {
      fill: var(--color-text-inverse);
    }
    .report-chip__mark {
      fill: none;
      stroke: var(--red-700);
      stroke-width: 1.75;
      stroke-linecap: round;
    }
  `,
})
export class ReportChip {
  readonly status = input.required<ReportStatus>();
  /** Distinct reporters; used by `reported` only. */
  readonly reporters = input(0);

  protected readonly text = computed(() => {
    if (this.status() === 'confirmed') {
      return 'Confirmed scam';
    }
    const count = this.reporters();
    return `Reported as a scam by ${count} ${count === 1 ? 'user' : 'users'} (not verified)`;
  });
}
