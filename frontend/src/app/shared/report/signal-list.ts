import { Component, computed, input } from '@angular/core';
import { CheckOutcome, ReportSignal } from './report-models';
import { SignalItem } from './signal-item';

/**
 * A report's signals (PRD §7): red flags first, then checks that couldn't run, with the other
 * checks folded away in a native `<details>`. Each signal is a `SignalItem` (evidence, source
 * kind, "AI-extracted"). Used by the student's report and by the TPO/admin evidence views,
 * which get the same signal shape (D-04). Group headings are h3, so place it under an h2.
 *
 *   <app-signal-list [signals]="report.signals" />
 */
@Component({
  selector: 'app-signal-list',
  imports: [SignalItem],
  template: `
    <h3 class="signals__heading">
      Red flags <span class="signals__count">({{ flags().length }})</span>
    </h3>
    @if (flags().length > 0) {
      <ul class="signals" role="list">
        @for (signal of flags(); track signal.code) {
          <li appSignalItem [signal]="signal"></li>
        }
      </ul>
    } @else {
      <p class="signals__none">None of the checks found a red flag.</p>
    }

    @if (unavailable().length > 0) {
      <h3 class="signals__heading">
        Checks that couldn't run <span class="signals__count">({{ unavailable().length }})</span>
      </h3>
      <ul class="signals" role="list">
        @for (signal of unavailable(); track signal.code) {
          <li appSignalItem [signal]="signal"></li>
        }
      </ul>
    }

    @if (others().length > 0) {
      <details class="signals__more">
        <summary>Other checks ({{ others().length }})</summary>
        <ul class="signals" role="list">
          @for (signal of others(); track signal.code) {
            <li appSignalItem [signal]="signal"></li>
          }
        </ul>
      </details>
    }
  `,
  styles: `
    :host {
      display: grid;
      gap: var(--space-3);
    }
    /* h3: put the list under an h2 ("What the checks found", "Evidence"). */
    .signals__heading {
      margin: var(--space-2) 0 0;
      font-size: var(--text-base);
    }
    .signals__heading:first-child {
      margin-top: 0;
    }
    .signals__count,
    .signals__none {
      color: var(--color-text-muted);
      font-weight: var(--weight-regular);
    }
    .signals__none {
      margin: 0;
    }
    .signals {
      display: grid;
      gap: var(--space-3);
      margin: 0;
      padding: 0;
      list-style: none;
    }
    /* Default list-item display keeps the native disclosure triangle; padding gives 44 px. */
    .signals__more summary {
      padding: 0.625rem 0;
      color: var(--color-primary);
      font-weight: var(--weight-semibold);
      cursor: pointer;
    }
    .signals__more[open] summary {
      margin-bottom: var(--space-2);
    }
  `,
})
export class SignalList {
  readonly signals = input.required<readonly ReportSignal[]>();

  protected readonly flags = computed(() => this.by('TRIGGERED'));
  protected readonly unavailable = computed(() => this.by('UNAVAILABLE'));
  protected readonly others = computed(() =>
    this.signals().filter((s) => s.outcome === 'PASSED' || s.outcome === 'NOT_APPLICABLE'),
  );

  private by(outcome: CheckOutcome): ReportSignal[] {
    return this.signals().filter((signal) => signal.outcome === outcome);
  }
}
