import {
  Component,
  ElementRef,
  Injector,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { focusAfterRender } from '../../core/a11y/focus';
import { AppDatePipe, AppDateTimePipe } from '../format/en-in';
import { Badge } from '../ui/badge';
import { Button } from '../ui/button';
import { ConfirmStep } from '../ui/confirm-step';
import { Notice } from '../ui/notice';
import { ReportChip } from '../ui/report-chip';
import { RiskBandIndicator } from '../ui/risk-band';
import { MARKER_LABELS, MARKER_SOURCE_LABELS, Marker, ReportEvidence } from './evidence-models';
import { overrideTitles } from './report-models';
import { SignalList } from './signal-list';

/**
 * A letter's evidence for a TPO or the admin (D-04): the band (word + shape + colour, verbatim
 * PRD §7.2 message, score), the sender domain, the shared-registry markers and every signal with
 * its evidence and source kind. Confirmed markers show "Confirmed scam"; markers only users
 * reported show "Reported as a scam by N users (not verified)", never as fact. Phone and UPI
 * markers show their masked hint and whether the student typed the value or it is in the letter.
 *
 * With `revertable` (admin), each confirmed marker gets a Revert button behind a confirm step
 * (D-13); `revert` emits the marker and the parent sends it with the marker's version.
 * Headings are h3: place it under an h2.
 */
@Component({
  selector: 'app-evidence',
  imports: [
    AppDatePipe,
    AppDateTimePipe,
    Badge,
    Button,
    ConfirmStep,
    Notice,
    ReportChip,
    RiskBandIndicator,
    SignalList,
  ],
  template: `
    @let e = evidence();
    <app-risk-band size="lg" [band]="e.band" [score]="e.score" />
    @if (overrides(); as titles) {
      <p class="evidence__note"><strong>Why High:</strong> {{ titles }} forced High.</p>
    }
    @if (e.partial) {
      <app-notice tone="warn" heading="Partial report">
        <p>Some checks couldn't run, so they added no points. They're listed below.</p>
      </app-notice>
    }
    <dl class="kv">
      <div>
        <dt>Sender domain</dt>
        <dd translate="no">{{ e.senderDomain ?? 'None found' }}</dd>
      </div>
      <div>
        <dt>Checked</dt>
        <dd>{{ e.completedAt | appDateTime }}</dd>
      </div>
    </dl>

    <h3 class="evidence__heading">Scam markers</h3>
    @if (e.markers.length === 0) {
      <p class="muted">No scam markers in the shared registry match this letter.</p>
    } @else {
      <ul class="markers" role="list">
        @for (marker of e.markers; track marker.id) {
          <li class="marker">
            <span class="marker__what">
              {{ labels[marker.type] }}
              @if (marker.domain || marker.hint; as value) {
                <span class="marker__value" translate="no">{{ value }}</span>
              }
              @if (sourceLabel(marker); as source) {
                <span class="marker__source">{{ source }}</span>
              }
            </span>
            @switch (marker.status) {
              @case ('CONFIRMED') {
                <span class="cluster">
                  <app-report-chip status="confirmed" />
                  @if (marker.confirmedAt) {
                    <span class="fine-print nowrap">since {{ marker.confirmedAt | appDate }}</span>
                  }
                </span>
              }
              @case ('UNVERIFIED') {
                <app-report-chip status="reported" [reporters]="marker.reporterCount" />
              }
              @default {
                <app-badge icon="cross">Not counted</app-badge>
              }
            }
            @if (revertable() && marker.status === 'CONFIRMED') {
              @if (confirmingId() === marker.id) {
                <app-confirm-step
                  question="Revert this marker? Letters that share it will no longer show “Confirmed scam”."
                  confirmLabel="Revert marker"
                  (confirmed)="confirmRevert(marker)"
                  (cancelled)="cancelRevert(marker)"
                />
              } @else {
                <button
                  appButton
                  type="button"
                  variant="secondary"
                  size="sm"
                  [attr.data-marker]="marker.id"
                  [loading]="revertingId() === marker.id"
                  [disabled]="revertingId() !== null"
                  (click)="confirmingId.set(marker.id)"
                >
                  Revert<span class="visually-hidden"> the {{ labels[marker.type] }} marker</span>
                </button>
              }
            }
          </li>
        }
      </ul>
    }

    <app-signal-list [signals]="e.signals" />
  `,
  styles: `
    :host {
      display: grid;
      gap: var(--space-4);
    }
    .evidence__note,
    app-notice {
      margin: 0;
    }
    .evidence__heading {
      margin: var(--space-2) 0 0;
      font-size: var(--text-base);
    }
    .markers {
      display: grid;
      gap: var(--space-3);
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .marker {
      display: grid;
      gap: var(--space-2);
      justify-items: start;
      padding-bottom: var(--space-3);
      border-bottom: 1px solid var(--color-border);
    }
    .marker__what {
      color: var(--color-text-muted);
      font-size: var(--text-sm);
    }
    .marker__value {
      display: block;
      color: var(--color-text);
      font-size: var(--text-base);
      font-weight: var(--weight-semibold);
      overflow-wrap: anywhere;
    }
    .marker__source {
      display: block;
    }
    app-confirm-step {
      justify-self: stretch;
    }
  `,
})
export class Evidence {
  readonly evidence = input.required<ReportEvidence>();
  /** Admin only: offer Revert on confirmed markers. */
  readonly revertable = input(false);
  /** The marker whose Revert is being sent; every Revert button is disabled meanwhile. */
  readonly revertingId = input<string | null>(null);
  readonly revert = output<Marker>();

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  protected readonly labels = MARKER_LABELS;
  protected readonly confirmingId = signal<string | null>(null);
  protected readonly overrides = computed(() => {
    const evidence = this.evidence();
    const titles = evidence.hardOverride ? overrideTitles(evidence.signals) : [];
    return titles.length > 0 ? titles.join(', ') : null;
  });

  /** Who supplied a phone or UPI marker's value; null for DOMAIN, SIMHASH and older answers. */
  protected sourceLabel(marker: Marker): string | null {
    if (marker.type !== 'PHONE' && marker.type !== 'UPI') {
      return null;
    }
    return marker.source ? MARKER_SOURCE_LABELS[marker.source] : null;
  }

  protected confirmRevert(marker: Marker): void {
    this.confirmingId.set(null);
    this.revert.emit(marker);
  }

  protected cancelRevert(marker: Marker): void {
    this.confirmingId.set(null);
    focusAfterRender(this.host.nativeElement, this.injector, `[data-marker="${marker.id}"]`);
  }
}
