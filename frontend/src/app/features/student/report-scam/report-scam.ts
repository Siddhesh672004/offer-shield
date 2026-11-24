import { HttpErrorResponse } from '@angular/common/http';
import {
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FieldState, FormField, FormRoot, form, validate } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Announcer } from '../../../core/a11y/announcer';
import {
  isSessionEnded,
  problemCode,
  problemDetail,
  problemViolations,
  retryAfterSeconds,
} from '../../../core/http/problem';
import { NO_MARKERS_RECORDED } from '../../../shared/report/evidence-models';
import {
  AppDatePipe,
  AppDateTimePipe,
  AppRupeesPipe,
  formatNumber,
  formatWait,
} from '../../../shared/format/en-in';
import { Badge } from '../../../shared/ui/badge';
import { Button } from '../../../shared/ui/button';
import { Field, fieldDescribedBy } from '../../../shared/ui/field';
import { Skeleton, Spinner } from '../../../shared/ui/loading';
import { Notice } from '../../../shared/ui/notice';
import { PageHeader } from '../../../shared/ui/page-header';
import { ScamReport, ScamReportApi } from '../scam-report-api';
import { SCAM_REPORT_STATUS } from '../scam-report-status';
import { SubmissionApi } from '../submission-api';

const MAX_DESCRIPTION = 2_000;
const MAX_AMOUNT = 100_000_000;
const DESCRIPTION_REQUIRED = 'Describe what happened.';
const REPORT_NOT_READY = 'This report is still being prepared. Try again in a minute.';
const AMOUNT_INVALID = `Enter a whole number of rupees from 0 to ${formatNumber(MAX_AMOUNT)}, or leave it empty.`;

/** "3,000" or "3 000" → 3000; null for blank; NaN for anything that isn't a whole number. */
function parseAmount(raw: string): number | null {
  const cleaned = raw.replace(/[,\s₹]/g, '');
  if (cleaned === '') {
    return null;
  }
  return /^\d{1,9}$/.test(cleaned) ? Number(cleaned) : Number.NaN;
}

function touchedError(state: FieldState<string>): string | null {
  const errors = state.errors();
  return state.touched() && errors.length > 0 ? (errors[0].message ?? 'Check this field.') : null;
}

/**
 * /app/submissions/:id/report-scam (PRD F6, §6.4): what happened and the amount asked, for one
 * of the user's own analysed letters. One report per letter (409 shows the existing one) and at
 * most 5 a day (429 says when to try again). The report's details become "Reported as a scam
 * (not verified)" for others until an admin confirms it.
 */
@Component({
  selector: 'app-report-scam',
  imports: [
    AppDatePipe,
    AppDateTimePipe,
    AppRupeesPipe,
    Badge,
    Button,
    Field,
    FormField,
    FormRoot,
    Notice,
    PageHeader,
    RouterLink,
    Skeleton,
    Spinner,
  ],
  templateUrl: './report-scam.html',
  styleUrl: './report-scam.css',
})
export class ReportScam {
  /** The submission ID, from the route. */
  readonly id = input.required<string>();

  private readonly submissions = inject(SubmissionApi);
  private readonly api = inject(ScamReportApi);
  private readonly announcer = inject(Announcer);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  protected readonly statuses = SCAM_REPORT_STATUS;
  protected readonly maxDescriptionLabel = formatNumber(MAX_DESCRIPTION);
  protected readonly letter = rxResource({
    params: () => this.id(),
    stream: ({ params }) => this.submissions.get(params),
  });
  protected readonly mine = rxResource({ stream: () => this.api.mine() });
  private readonly created = signal<ScamReport | null>(null);

  protected readonly loading = computed(() => this.letter.isLoading() || this.mine.isLoading());
  protected readonly letterMissing = computed(() => {
    const error = this.letter.error();
    return error instanceof HttpErrorResponse && error.status === 404;
  });
  protected readonly analysed = computed(() => {
    const status = this.letter.hasValue() ? this.letter.value().status : null;
    return status === 'READY' || status === 'PARTIAL';
  });
  protected readonly existing = computed(
    () =>
      this.created() ??
      (this.mine.hasValue()
        ? (this.mine.value().find((report) => report.submissionId === this.id()) ?? null)
        : null),
  );
  /** The user's own reports didn't load, so we can't tell whether this letter has one. */
  protected readonly mineFailed = computed(() => {
    const error = this.mine.error();
    return error !== undefined && !isSessionEnded(error);
  });
  /** The report just sent recorded no registry marker (an older answer has no count). */
  protected readonly noMarkers = computed(() => this.created()?.markersRecorded === 0);
  protected readonly noMarkersText = NO_MARKERS_RECORDED;
  protected readonly alreadyReported = signal(false);
  protected readonly sending = signal(false);
  protected readonly sendError = signal<string | null>(null);
  /** 409 `REPORT_NOT_READY`: the letter's report is still being prepared, so offer "Try again". */
  protected readonly notReady = signal(false);
  private readonly serverErrors = signal<{ description?: string; amountAsked?: string }>({});

  protected readonly model = signal({ description: '', amount: '' });
  protected readonly scamForm = form(
    this.model,
    (path) => {
      validate(path.description, ({ value }) => {
        if (value().trim() === '') {
          return { kind: 'required', message: DESCRIPTION_REQUIRED };
        }
        return value().length > MAX_DESCRIPTION
          ? { kind: 'maxLength', message: 'Use 2,000 characters or fewer.' }
          : undefined;
      });
      validate(path.amount, ({ value }) => {
        const amount = parseAmount(value());
        return amount !== null && (Number.isNaN(amount) || amount > MAX_AMOUNT)
          ? { kind: 'amount', message: AMOUNT_INVALID }
          : undefined;
      });
    },
    {
      submission: {
        action: async () => {
          await this.send();
          return undefined;
        },
        onInvalid: () => {
          this.announcer.announce('Check the details marked below.');
          this.focusFirstProblem();
        },
      },
    },
  );

  protected readonly descriptionLength = computed(() =>
    formatNumber(this.model().description.length),
  );
  protected readonly descriptionError = computed(
    () => touchedError(this.scamForm.description()) ?? this.serverErrors().description ?? null,
  );
  protected readonly amountError = computed(
    () => touchedError(this.scamForm.amount()) ?? this.serverErrors().amountAsked ?? null,
  );

  protected describedBy(fieldId: string, hint: boolean, error: string | null): string | null {
    return fieldDescribedBy(fieldId, { hint, error: error !== null });
  }

  private async send(): Promise<void> {
    if (this.sending()) {
      return;
    }
    this.sending.set(true);
    this.sendError.set(null);
    this.notReady.set(false);
    this.serverErrors.set({});
    const { description, amount } = this.model();
    try {
      const report = await firstValueFrom(
        this.api.report({
          submissionId: this.id(),
          description: description.trim(),
          amountAsked: parseAmount(amount),
        }),
      );
      this.created.set(report);
      const sent = 'Scam report sent. An admin will review it.';
      this.announcer.announce(
        report.markersRecorded === 0 ? `${sent} ${NO_MARKERS_RECORDED}` : sent,
      );
      this.focusStatus();
    } catch (error) {
      if (!isSessionEnded(error)) {
        this.showError(error);
      }
    } finally {
      this.sending.set(false);
    }
  }

  private showError(error: unknown): void {
    const code = problemCode(error);
    if (code === 'REPORT_NOT_READY') {
      const message = problemDetail(error, REPORT_NOT_READY);
      this.notReady.set(true);
      this.sendError.set(message);
      this.announcer.announce(message);
      return;
    }
    if (code === 'SCAM_REPORT_EXISTS') {
      this.alreadyReported.set(true);
      this.mine.reload();
      this.announcer.announce('You already reported this letter.');
      this.focusStatus();
      return;
    }
    if (code === 'VALIDATION_FAILED' || code === 'DESCRIPTION_REQUIRED') {
      const errors: { description?: string; amountAsked?: string } = {};
      if (code === 'DESCRIPTION_REQUIRED') {
        errors.description = DESCRIPTION_REQUIRED;
      }
      for (const violation of problemViolations(error)) {
        if (violation.field === 'description') {
          errors.description = 'Describe what happened in 2,000 characters or fewer.';
        } else if (violation.field === 'amountAsked') {
          errors.amountAsked = AMOUNT_INVALID;
        }
      }
      if (errors.description || errors.amountAsked) {
        this.serverErrors.set(errors);
        this.announcer.announce('Check the details marked below.');
        this.focusFirstProblem();
        return;
      }
    }
    const message = messageFor(error, code);
    this.sendError.set(message);
    this.announcer.announce(message);
  }

  private focusStatus(): void {
    afterNextRender(
      () => this.host.nativeElement.querySelector<HTMLElement>('#scam-status-heading')?.focus(),
      { injector: this.injector },
    );
  }

  private focusFirstProblem(): void {
    afterNextRender(
      () => {
        const selector = this.descriptionError()
          ? '#scam-description'
          : this.amountError()
            ? '#scam-amount'
            : null;
        if (selector) {
          this.host.nativeElement.querySelector<HTMLElement>(selector)?.focus();
        }
      },
      { injector: this.injector },
    );
  }
}

function messageFor(error: unknown, code: string | undefined): string {
  const status = error instanceof HttpErrorResponse ? error.status : -1;
  const wait = (): string => {
    const seconds = retryAfterSeconds(error);
    return seconds === null ? 'a few hours' : formatWait(seconds);
  };
  if (code === 'SCAM_REPORT_LIMIT') {
    return `You can report up to 5 scams a day. Try again in ${wait()}.`;
  }
  if (status === 429) {
    return `Too many requests at once. Try again in ${wait()}.`;
  }
  if (status === 404) {
    return "This letter can't be reported: its report may have been deleted.";
  }
  if (code === 'CSRF_INVALID') {
    return 'This page needs a refresh. Reload it and try again.';
  }
  if (status === 403) {
    return 'Only student accounts can report a scam.';
  }
  if (status === 0) {
    return "Couldn't reach OfferShield. Check your connection and try again.";
  }
  return "Couldn't send your report. Try again.";
}
