import { Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { isSessionEnded } from '../../../core/http/problem';
import { AppDatePipe, AppRupeesPipe } from '../../../shared/format/en-in';
import { Badge } from '../../../shared/ui/badge';
import { Button } from '../../../shared/ui/button';
import { ScamReportApi } from '../scam-report-api';
import { SCAM_REPORT_STATUS } from '../scam-report-status';

/**
 * The "Report a scam" card next to a report (PRD F6): the user's own scam report for this
 * letter and its state, or (when `canReport`) a link to the report form. If the user's reports
 * don't load, it says so (with "Try again") rather than acting as if there were none.
 */
@Component({
  selector: 'app-scam-report-card',
  imports: [AppDatePipe, AppRupeesPipe, Badge, Button, RouterLink],
  template: `
    @if (current(); as report) {
      <section class="card" aria-labelledby="scam-card-heading">
        <div class="card__header">
          <h2 id="scam-card-heading" class="card__title">Your scam report</h2>
          <app-badge [tone]="statuses[report.status].tone" [icon]="statuses[report.status].icon">{{
            statuses[report.status].label
          }}</app-badge>
        </div>
        <p class="muted">{{ statuses[report.status].text }}</p>
        <p class="fine-print">
          <span class="nowrap">Sent {{ report.createdAt | appDate }}</span>
          @if (report.amountAsked !== null) {
            · <span class="nowrap">amount asked {{ report.amountAsked | appRupees }}</span>
          }
        </p>
      </section>
    } @else if (loadFailed()) {
      <section class="card" aria-labelledby="scam-card-heading">
        <h2 id="scam-card-heading" class="card__title">Report a scam</h2>
        <p class="muted card__description scam-card__load-error">
          Couldn't check whether you already reported this letter. Check your connection and try
          again.
        </p>
        <button
          appButton
          type="button"
          variant="secondary"
          size="sm"
          class="scam-card__retry"
          (click)="mine.reload()"
        >
          Try again
        </button>
      </section>
    } @else if (canReport()) {
      <section class="card" aria-labelledby="scam-card-heading">
        <h2 id="scam-card-heading" class="card__title">Did this turn out to be a scam?</h2>
        <p class="muted card__description">
          If they asked you for money or stopped answering, warn other students. An admin reviews
          every report before it counts as confirmed.
        </p>
        <a
          appButton
          variant="secondary"
          [block]="true"
          class="scam-card__link"
          [routerLink]="['/app/submissions', submissionId(), 'report-scam']"
          >Report this letter as a scam</a
        >
      </section>
    }
  `,
  styles: `
    .scam-card__link,
    .scam-card__retry {
      margin-top: var(--space-4);
    }
  `,
})
export class ScamReportCard {
  readonly submissionId = input.required<string>();
  /** A student account looking at an analysed letter (the API checks again). */
  readonly canReport = input(false);

  private readonly api = inject(ScamReportApi);
  protected readonly mine = rxResource({ stream: () => this.api.mine() });

  protected readonly statuses = SCAM_REPORT_STATUS;
  protected readonly current = computed(() =>
    this.mine.hasValue()
      ? (this.mine.value().find((report) => report.submissionId === this.submissionId()) ?? null)
      : null,
  );
  /** The user's reports didn't load, so we can't tell whether this letter has one. */
  protected readonly loadFailed = computed(() => {
    const error = this.mine.error();
    return error !== undefined && !isSessionEnded(error);
  });
}
