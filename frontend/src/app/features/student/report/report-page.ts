import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import {
  BehaviorSubject,
  EMPTY,
  Observable,
  catchError,
  combineLatest,
  concatWith,
  defer,
  ignoreElements,
  switchMap,
  tap,
} from 'rxjs';
import { Announcer } from '../../../core/a11y/announcer';
import { Session } from '../../../core/auth/session';
import { isSessionEnded } from '../../../core/http/problem';
import { AppDateTimePipe } from '../../../shared/format/en-in';
import { checkTitles, overrideTitles } from '../../../shared/report/report-models';
import { SignalList } from '../../../shared/report/signal-list';
import { AiTag } from '../../../shared/ui/ai-tag';
import { Badge } from '../../../shared/ui/badge';
import { Button } from '../../../shared/ui/button';
import { Skeleton, Spinner } from '../../../shared/ui/loading';
import { Notice } from '../../../shared/ui/notice';
import { PageHeader } from '../../../shared/ui/page-header';
import { ReportChip } from '../../../shared/ui/report-chip';
import { RISK_BAND_LABELS, RiskBandIndicator } from '../../../shared/ui/risk-band';
import { Report, ReportApi } from '../report-api';
import { OfferShieldUnreachableError, Submission, SubmissionApi } from '../submission-api';
import { SOURCE_LABELS } from '../submission-status';
import { AskTpo } from './ask-tpo';
import { ExplainSummary } from './explain';
import { ScamReportCard } from './scam-report-card';

type Phase =
  | 'checking'
  | 'timeout'
  | 'failed'
  | 'deleted'
  | 'not-found'
  | 'error'
  | 'loading-report'
  | 'report';

/** D-14, verbatim. */
export const STILL_PROCESSING = 'Still processing — check My submissions';
const UNREACHABLE = "Couldn't reach OfferShield";
const LOAD_FAILED = "Couldn't load this report";

/**
 * /app/submissions/:id (PRD F4, D-14): polls the letter's status every 2 s (waiting for each
 * answer, at most 3 minutes), then shows the report: the band as word + shape + colour with the
 * PRD §7.2 message, the score and any forced-High note, the registry's two trust levels kept
 * apart, partial and "AI unavailable" notices, every signal with its evidence and source kind,
 * and the AI-extracted details. Beside it: "Explain simply", "Ask my TPO" (on-campus members
 * only) and "Report a scam". Everything from the API is shown as text, never as HTML.
 */
@Component({
  selector: 'app-report-page',
  imports: [
    AiTag,
    AppDateTimePipe,
    AskTpo,
    Badge,
    Button,
    ExplainSummary,
    Notice,
    PageHeader,
    ReportChip,
    RiskBandIndicator,
    RouterLink,
    ScamReportCard,
    SignalList,
    Skeleton,
    Spinner,
  ],
  templateUrl: './report-page.html',
  styleUrl: './report-page.css',
})
export class ReportPage {
  /** The submission ID, from the route (`withComponentInputBinding`). */
  readonly id = input.required<string>();

  private readonly submissions = inject(SubmissionApi);
  private readonly reports = inject(ReportApi);
  private readonly session = inject(Session);
  private readonly announcer = inject(Announcer);
  private readonly restarts = new BehaviorSubject<void>(undefined);

  protected readonly stillProcessing = STILL_PROCESSING;
  protected readonly sourceLabels = SOURCE_LABELS;
  protected readonly phase = signal<Phase>('checking');
  /** The 'error' phase's heading: polling never reached OfferShield, or a load failed. */
  protected readonly errorHeading = signal(LOAD_FAILED);
  protected readonly submission = signal<Submission | null>(null);
  protected readonly report = signal<Report | null>(null);

  protected readonly isStudent = computed(() => this.session.me()?.role === 'USER');
  protected readonly canAskTpo = computed(() => {
    const me = this.session.me();
    return (
      this.phase() === 'report' &&
      me?.role === 'USER' &&
      me.membershipStatus === 'APPROVED' &&
      this.submission()?.onCampus === true
    );
  });
  protected readonly canReportScam = computed(() => this.phase() === 'report' && this.isStudent());

  protected readonly overrideNote = computed(() => {
    const report = this.report();
    if (!report || report.band !== 'HIGH' || !report.hardOverride) {
      return null;
    }
    const titles = overrideTitles(report.signals);
    if (titles.length === 0) {
      return null;
    }
    return titles.length === 1
      ? `${titles[0]}. This check sets the band to High whatever the score.`
      : `${titles.join(', ')}. These checks set the band to High whatever the score.`;
  });
  protected readonly unavailableTitles = computed(() => {
    const report = this.report();
    return report ? checkTitles(report.unavailableChecks, report.signals).join(', ') : '';
  });

  constructor() {
    combineLatest([toObservable(this.id), this.restarts])
      .pipe(
        switchMap(([id]) => this.track(id)),
        takeUntilDestroyed(),
      )
      .subscribe();
  }

  /** After the 3-minute cap or an error: start polling again. */
  protected checkAgain(): void {
    this.restarts.next();
  }

  private track(id: string): Observable<unknown> {
    this.phase.set('checking');
    this.report.set(null);
    if (this.submission()?.id !== id) {
      this.submission.set(null);
    }
    let last: Submission | null = null;
    return this.submissions.watch(id).pipe(
      tap((submission) => {
        last = submission;
        this.submission.set(submission);
      }),
      ignoreElements(),
      concatWith(defer(() => this.settle(id, last))),
      catchError((error: unknown) => {
        this.fail(error, 'not-found');
        return EMPTY;
      }),
    );
  }

  private settle(id: string, last: Submission | null): Observable<unknown> {
    switch (last?.status ?? 'QUEUED') {
      case 'QUEUED':
        this.phase.set('timeout');
        this.announcer.announce(STILL_PROCESSING);
        return EMPTY;
      case 'FAILED':
        this.phase.set('failed');
        this.announcer.announce("We couldn't check this letter.");
        return EMPTY;
      case 'DELETED':
        this.phase.set('deleted');
        this.announcer.announce('This letter was deleted after 30 days.');
        return EMPTY;
      default:
        this.phase.set('loading-report');
        return this.reports.get(id).pipe(
          tap((report) => {
            this.report.set(report);
            this.phase.set('report');
            this.announcer.announce(`Report ready: ${RISK_BAND_LABELS[report.band]}.`);
          }),
          catchError((error: unknown) => {
            this.fail(error, 'deleted');
            return EMPTY;
          }),
        );
    }
  }

  /**
   * A 404 means `notFoundPhase`; a 401 is handled by the interceptor; anything else can retry.
   * Polling that never reached OfferShield says so, not "Still processing".
   */
  private fail(error: unknown, notFoundPhase: Phase): void {
    if (isSessionEnded(error)) {
      return;
    }
    if (error instanceof HttpErrorResponse && error.status === 404) {
      this.phase.set(notFoundPhase);
      this.announcer.announce(
        notFoundPhase === 'deleted'
          ? 'This report is no longer available.'
          : "We couldn't find this letter.",
      );
      return;
    }
    const unreachable = error instanceof OfferShieldUnreachableError;
    this.errorHeading.set(unreachable ? UNREACHABLE : LOAD_FAILED);
    this.phase.set('error');
    this.announcer.announce(
      unreachable
        ? `${UNREACHABLE}. Check your connection and try again.`
        : `${LOAD_FAILED}. Try again.`,
    );
  }
}
