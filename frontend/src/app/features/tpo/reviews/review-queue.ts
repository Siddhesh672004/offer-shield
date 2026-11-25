import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Session } from '../../../core/auth/session';
import { AppDateTimePipe } from '../../../shared/format/en-in';
import { ReportEvidence } from '../../../shared/report/evidence-models';
import { Badge } from '../../../shared/ui/badge';
import { Button } from '../../../shared/ui/button';
import { EmptyState } from '../../../shared/ui/empty-state';
import { Skeleton } from '../../../shared/ui/loading';
import { Notice } from '../../../shared/ui/notice';
import { PageHeader } from '../../../shared/ui/page-header';
import { ReportChip } from '../../../shared/ui/report-chip';
import { RiskBandIndicator } from '../../../shared/ui/risk-band';
import { ReviewQueueApi } from './reviews-api';

/** "Confirmed scam" if any marker is confirmed, else the most-reported unconfirmed marker's count. */
function registrySummary(evidence: ReportEvidence): { confirmed: boolean; reporters: number } {
  const confirmed = evidence.markers.some((marker) => marker.status === 'CONFIRMED');
  const reporters = Math.max(
    0,
    ...evidence.markers
      .filter((marker) => marker.status === 'UNVERIFIED')
      .map((marker) => marker.reporterCount),
  );
  return { confirmed, reporters };
}

/**
 * /tpo/reviews (PRD F5, §12 "Review queue"): the open cases of the TPO's own college, oldest
 * first, each with the student, the band (word + shape + colour) and the registry status, kept
 * apart: "Confirmed scam" vs "Reported as a scam by N users (not verified)".
 */
@Component({
  selector: 'app-review-queue',
  imports: [
    AppDateTimePipe,
    Badge,
    Button,
    EmptyState,
    Notice,
    PageHeader,
    ReportChip,
    RiskBandIndicator,
    RouterLink,
    Skeleton,
  ],
  template: `
    <app-page-header heading="Review queue">
      <p>
        Offers that students at {{ collegeName() }} asked you to check. Open one to see the evidence
        and decide.
      </p>
    </app-page-header>

    @if (cases.isLoading() && !cases.hasValue()) {
      <div class="card" aria-busy="true">
        <app-skeleton [lines]="4" />
        <span class="visually-hidden">Loading the review queue…</span>
      </div>
    } @else if (cases.error()) {
      <app-notice tone="danger" heading="Couldn't load the review queue">
        <p>Check your connection and try again.</p>
        <button appButton type="button" variant="secondary" size="sm" (click)="cases.reload()">
          Try again
        </button>
      </app-notice>
    } @else if (rows().length > 0) {
      <p class="muted queue-count">{{ countLabel() }}</p>
      <ul class="list" role="list">
        @for (row of rows(); track row.item.id) {
          <li class="case">
            <div class="case__who">
              <a class="case__link" [routerLink]="['/tpo/reviews', row.item.id]">{{
                row.item.studentName ?? 'A student'
              }}</a>
              <span class="case__meta">
                @if (row.item.studentEmail) {
                  <span translate="no">{{ row.item.studentEmail }}</span> ·
                }
                <span class="nowrap">asked {{ row.item.createdAt | appDateTime }}</span>
              </span>
            </div>
            <div class="cluster">
              @if (row.item.evidence; as evidence) {
                <app-risk-band [band]="evidence.band" />
                @if (row.confirmed) {
                  <app-report-chip status="confirmed" />
                } @else if (row.reporters > 0) {
                  <app-report-chip status="reported" [reporters]="row.reporters" />
                }
              } @else {
                <app-badge>Report deleted</app-badge>
              }
            </div>
          </li>
        }
      </ul>
    } @else {
      <app-empty-state heading="No offers to review" icon="inbox">
        <p>When a student at {{ collegeName() }} asks you to check an offer, it appears here.</p>
      </app-empty-state>
    }
  `,
  styles: `
    .queue-count {
      margin-bottom: var(--space-3);
    }
    .case {
      display: grid;
      gap: var(--space-2) var(--space-5);
    }
    .case__who {
      display: grid;
      min-width: 0;
    }
    .case__link {
      display: inline-flex;
      align-items: center;
      min-height: var(--touch);
      font-weight: var(--weight-semibold);
    }
    .case__meta {
      margin-top: calc(-1 * var(--space-2));
      color: var(--color-text-muted);
      font-size: var(--text-sm);
      overflow-wrap: anywhere;
    }
    @media (min-width: 48rem) {
      .case {
        grid-template-columns: minmax(0, 1fr) auto;
        align-items: center;
      }
    }
  `,
})
export class ReviewQueue {
  private readonly api = inject(ReviewQueueApi);
  private readonly session = inject(Session);

  protected readonly collegeName = computed(
    () => this.session.me()?.college?.name ?? 'your college',
  );
  protected readonly cases = rxResource({ stream: () => this.api.queue() });
  protected readonly rows = computed(() =>
    (this.cases.hasValue() ? this.cases.value() : []).map((item) => ({
      item,
      ...(item.evidence ? registrySummary(item.evidence) : { confirmed: false, reporters: 0 }),
    })),
  );
  protected readonly countLabel = computed(() => {
    const count = this.rows().length;
    return `${count} ${count === 1 ? 'offer' : 'offers'} waiting, oldest first.`;
  });
}
