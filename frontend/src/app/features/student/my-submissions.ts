import { Component, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { AppDatePipe, AppDateTimePipe } from '../../shared/format/en-in';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { EmptyState } from '../../shared/ui/empty-state';
import { Skeleton } from '../../shared/ui/loading';
import { Notice } from '../../shared/ui/notice';
import { SubmissionApi } from './submission-api';
import { SOURCE_LABELS, SUBMISSION_STATUS } from './submission-status';

/**
 * "My submissions" (PRD §12): the user's letters, newest first, each linking to its report with
 * its state (checking, ready, partial, couldn't check, deleted) and when it will be deleted.
 */
@Component({
  selector: 'app-my-submissions',
  imports: [AppDatePipe, AppDateTimePipe, Badge, Button, EmptyState, RouterLink, Skeleton, Notice],
  template: `
    <section aria-labelledby="submissions-heading">
      <h2 id="submissions-heading">My submissions</h2>
      @if (letters.isLoading() && !letters.hasValue()) {
        <div class="card" aria-busy="true">
          <app-skeleton [lines]="3" />
          <span class="visually-hidden">Loading your letters…</span>
        </div>
      } @else if (letters.error()) {
        <app-notice tone="danger" heading="Couldn't load your letters">
          <p>Check your connection and try again.</p>
          <button
            appButton
            type="button"
            variant="secondary"
            size="sm"
            class="submissions-retry"
            (click)="letters.reload()"
          >
            Try again
          </button>
        </app-notice>
      } @else if (letters.hasValue() && letters.value().length > 0) {
        <ul class="list" role="list">
          @for (letter of letters.value(); track letter.id) {
            <li class="submission list__row">
              <div class="submission__text">
                <a class="submission__link" [routerLink]="['/app/submissions', letter.id]"
                  >Letter sent {{ letter.createdAt | appDateTime }}</a
                >
                <span class="submission__meta">
                  {{ sources[letter.sourceType] }}
                  @if (letter.status !== 'DELETED') {
                    · kept until {{ letter.expiresAt | appDate }}
                  }
                </span>
              </div>
              <app-badge
                [tone]="statuses[letter.status].tone"
                [icon]="statuses[letter.status].icon"
                >{{ statuses[letter.status].label }}</app-badge
              >
            </li>
          }
        </ul>
      } @else {
        <app-empty-state heading="No letters checked yet" icon="document" [headingLevel]="3">
          <p>Each offer letter you check and its risk report will be listed here for 30 days.</p>
          @if (canUpload()) {
            <a appButton emptyStateActions routerLink="/app/upload">Check an offer letter</a>
          }
        </app-empty-state>
      }
    </section>
  `,
  styles: `
    h2 {
      margin-bottom: var(--space-4);
    }
    .submission__text {
      display: grid;
      min-width: 0;
    }
    .submission__link {
      display: inline-flex;
      align-items: center;
      min-height: var(--touch);
      font-weight: var(--weight-semibold);
    }
    .submission__meta {
      margin-top: calc(-1 * var(--space-2));
      color: var(--color-text-muted);
      font-size: var(--text-sm);
    }
    .list > li {
      padding-block: var(--space-2) var(--space-3);
    }
    app-notice {
      margin: 0;
    }
  `,
})
export class MySubmissions {
  /** Student accounts may upload; TPOs and admins see their list (if any) without the action. */
  readonly canUpload = input(true);

  private readonly api = inject(SubmissionApi);

  protected readonly letters = rxResource({ stream: () => this.api.list() });
  protected readonly statuses = SUBMISSION_STATUS;
  protected readonly sources = SOURCE_LABELS;
}
