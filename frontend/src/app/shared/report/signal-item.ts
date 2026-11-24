import { Component, computed, input } from '@angular/core';
import { SourceKindTag } from '../ui/source-kind';
import { CheckOutcome, ReportSignal } from './report-models';

const OUTCOME_LABELS: Readonly<Record<CheckOutcome, string>> = {
  TRIGGERED: 'Red flag',
  UNAVAILABLE: "Couldn't run",
  PASSED: 'No red flag',
  NOT_APPLICABLE: "Didn't apply",
};

/**
 * One signal as a list item: title, outcome (word + icon), its effect on the score, the
 * explanation, the evidence as plain text, and the source kind (+ "AI-extracted").
 *
 *   <ul role="list"><li appSignalItem [signal]="signal"></li></ul>
 */
@Component({
  selector: 'li[appSignalItem]',
  imports: [SourceKindTag],
  host: { '[class]': '"signal signal--" + outcomeClass()' },
  template: `
    <div class="signal__head">
      <span class="signal__title">{{ signal().title }}</span>
      <span class="signal__outcome">
        <svg viewBox="0 0 16 16" aria-hidden="true" focusable="false">
          @switch (signal().outcome) {
            @case ('TRIGGERED') {
              <path d="M3.5 14.5v-12M3.5 2.5h8.25l-1.75 3 1.75 3H3.5" />
            }
            @case ('UNAVAILABLE') {
              <circle cx="8" cy="8" r="5.75" stroke-dasharray="2.4 2.1" />
            }
            @case ('PASSED') {
              <path d="m3.75 8.25 2.75 2.75 5.75-6" />
            }
            @default {
              <path d="M4 8h8" />
            }
          }
        </svg>
        {{ outcomeLabel() }}
        @if (effect(); as effect) {
          <span class="signal__effect">· {{ effect }}</span>
        }
      </span>
    </div>
    <p class="signal__explanation">{{ signal().explanation }}</p>
    @if (signal().evidence; as evidence) {
      <div class="signal__evidence">
        <span class="signal__evidence-label">Evidence</span>
        <blockquote class="plain-text">{{ evidence }}</blockquote>
      </div>
    }
    <app-source-kind [kind]="signal().sourceKind" [aiExtracted]="signal().aiExtracted" />
  `,
  styleUrl: './signal-item.css',
})
export class SignalItem {
  readonly signal = input.required<ReportSignal>();

  protected readonly outcomeClass = computed(() =>
    this.signal().outcome.toLowerCase().replace('_', '-'),
  );
  protected readonly outcomeLabel = computed(() => OUTCOME_LABELS[this.signal().outcome]);

  /** What the signal did to the score. */
  protected readonly effect = computed(() => {
    const signal = this.signal();
    if (signal.outcome === 'UNAVAILABLE') {
      return 'Not counted';
    }
    if (signal.outcome !== 'TRIGGERED') {
      return null;
    }
    if (signal.hardOverride) {
      return 'Sets High';
    }
    return signal.points > 0 ? `+${signal.points} points` : null;
  });
}
