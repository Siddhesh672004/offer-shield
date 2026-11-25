import { HttpErrorResponse } from '@angular/common/http';
import {
  Component,
  ElementRef,
  Injector,
  computed,
  inject,
  input,
  linkedSignal,
  signal,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Observable, firstValueFrom } from 'rxjs';
import { Announcer } from '../../../core/a11y/announcer';
import { focusAfterRender } from '../../../core/a11y/focus';
import { isSessionEnded, problemCode } from '../../../core/http/problem';
import { AppDateTimePipe, AppRupeesPipe } from '../../../shared/format/en-in';
import { Evidence } from '../../../shared/report/evidence';
import { Marker, NO_MARKERS_RECORDED, PdfLink } from '../../../shared/report/evidence-models';
import { PdfLinkButton } from '../../../shared/report/pdf-link';
import { Badge, BadgeIcon, BadgeTone } from '../../../shared/ui/badge';
import { Button } from '../../../shared/ui/button';
import { ConfirmStep } from '../../../shared/ui/confirm-step';
import { EmptyState } from '../../../shared/ui/empty-state';
import { Skeleton } from '../../../shared/ui/loading';
import { Notice, NoticeTone } from '../../../shared/ui/notice';
import { PageHeader } from '../../../shared/ui/page-header';
import {
  AdminScamReport,
  AdminScamReportsApi,
  ScamReportDecision,
  ScamReportStatus,
} from './scam-reports-api';

interface Outcome {
  readonly tone: NoticeTone;
  readonly text: string;
}

interface StatusView {
  readonly tab: string;
  readonly label: string;
  readonly tone: BadgeTone;
  readonly icon: BadgeIcon;
  readonly empty: string;
  readonly emptyText: string;
}

const STATUS: Readonly<Record<ScamReportStatus, StatusView>> = {
  OPEN: {
    tab: 'Open',
    label: 'Waiting for you',
    tone: 'warn',
    icon: 'clock',
    empty: 'No open reports',
    emptyText: 'When a user reports a letter as a scam, the report waits here for your decision.',
  },
  CONFIRMED: {
    tab: 'Confirmed',
    label: 'Confirmed',
    tone: 'danger',
    icon: 'check',
    empty: 'No confirmed reports',
    emptyText: 'Reports you confirm are listed here, newest first. You can revert their markers.',
  },
  REJECTED: {
    tab: 'Rejected',
    label: 'Rejected',
    tone: 'neutral',
    icon: 'cross',
    empty: 'No rejected reports',
    emptyText: 'Reports you reject are listed here, newest first.',
  },
};

const TABS: readonly ScamReportStatus[] = ['OPEN', 'CONFIRMED', 'REJECTED'];
const REFRESHED = 'The list has been refreshed.';

/**
 * /admin/scam-reports (PRD §6.4 steps 5–7, §12 "Unverified scam reports", D-04, D-05, D-13):
 * reports by status, each with the reporter's words (a claim, not a fact), the amount asked, the
 * letter's evidence and its PDF. Confirm or reject an open report (reject behind a confirm
 * step), and revert a confirmed marker. Every change carries its version as `If-Match`; a 409
 * refreshes the list and says why.
 */
@Component({
  selector: 'app-admin-scam-reports',
  imports: [
    AppDateTimePipe,
    AppRupeesPipe,
    Badge,
    Button,
    ConfirmStep,
    EmptyState,
    Evidence,
    Notice,
    PageHeader,
    PdfLinkButton,
    RouterLink,
    Skeleton,
  ],
  templateUrl: './admin-scam-reports.html',
  styleUrl: './admin-scam-reports.css',
})
export class AdminScamReports {
  /** `?status=` from the URL; anything unknown shows the open reports. */
  readonly status = input<string | undefined>();

  private readonly api = inject(AdminScamReportsApi);
  private readonly announcer = inject(Announcer);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  protected readonly tabs = TABS;
  protected readonly views = STATUS;
  protected readonly current = computed<ScamReportStatus>(() => {
    const status = this.status();
    return status === 'CONFIRMED' || status === 'REJECTED' ? status : 'OPEN';
  });
  protected readonly reports = rxResource({
    params: () => this.current(),
    stream: ({ params }) => this.api.list(params),
  });
  protected readonly list = computed(() => (this.reports.hasValue() ? this.reports.value() : []));

  /** The report whose decision is being sent. */
  protected readonly busyId = signal<string | null>(null);
  /** The marker whose Revert is being sent. */
  protected readonly revertingId = signal<string | null>(null);
  protected readonly busy = computed(() => this.busyId() !== null || this.revertingId() !== null);
  /** Locks every Revert button while anything is being sent. */
  protected readonly revertLock = computed(
    () => this.revertingId() ?? (this.busyId() ? 'decision' : null),
  );
  /** Both reset when another status tab opens. */
  protected readonly rejectingId = linkedSignal<ScamReportStatus, string | null>({
    source: this.current,
    computation: () => null,
  });
  protected readonly outcome = linkedSignal<ScamReportStatus, Outcome | null>({
    source: this.current,
    computation: () => null,
  });
  private readonly pdfFetchers = new Map<string, () => Observable<PdfLink>>();

  protected pdfFetch(reportId: string): () => Observable<PdfLink> {
    let fetcher = this.pdfFetchers.get(reportId);
    if (!fetcher) {
      fetcher = () => this.api.pdfLink(reportId);
      this.pdfFetchers.set(reportId, fetcher);
    }
    return fetcher;
  }

  protected confirm(report: AdminScamReport): void {
    void this.decide(report, 'CONFIRM', (decided) =>
      // 0 = nothing about this letter could become a marker; an older answer has no count.
      decided.markersRecorded === 0
        ? {
            tone: 'warn',
            text: `Report confirmed. ${NO_MARKERS_RECORDED} The reporter will get an email.`,
          }
        : {
            tone: 'success',
            text:
              'Report confirmed. Letters that share its details now show “Confirmed scam”. ' +
              'The reporter will get an email.',
          },
    );
  }

  protected reject(report: AdminScamReport): void {
    this.rejectingId.set(null);
    void this.decide(report, 'REJECT', () => ({
      tone: 'success',
      text:
        "Report rejected. Its markers no longer count this reporter's report. " +
        'The reporter will get an email.',
    }));
  }

  protected cancelReject(report: AdminScamReport): void {
    this.rejectingId.set(null);
    focusAfterRender(
      this.host.nativeElement,
      this.injector,
      `#report-${report.id} .report__reject`,
    );
  }

  protected async revert(report: AdminScamReport, marker: Marker): Promise<void> {
    if (this.busy()) {
      return;
    }
    this.revertingId.set(marker.id);
    this.outcome.set(null);
    try {
      await firstValueFrom(this.api.revert(marker));
      await this.refresh();
      this.show(
        {
          tone: 'success',
          text: "Marker reverted: it's no longer confirmed, so letters that share it don't show “Confirmed scam”.",
        },
        `#report-${report.id}-heading`,
      );
    } catch (error) {
      if (!isSessionEnded(error)) {
        await this.failed(error, revertProblem(error));
      }
    } finally {
      this.revertingId.set(null);
    }
  }

  private async decide(
    report: AdminScamReport,
    decision: ScamReportDecision,
    done: (decided: AdminScamReport) => Outcome,
  ): Promise<void> {
    if (this.busy()) {
      return;
    }
    this.busyId.set(report.id);
    this.outcome.set(null);
    const list = this.list();
    const index = list.findIndex((item) => item.id === report.id);
    const next = list[index + 1] ?? list[index - 1];
    try {
      const decided = await firstValueFrom(this.api.decide(report, decision));
      await this.refresh();
      this.show(done(decided), next ? `#report-${next.id}-heading` : '.empty-state__heading');
    } catch (error) {
      if (!isSessionEnded(error)) {
        await this.failed(error, decisionProblem(error));
      }
    } finally {
      this.busyId.set(null);
    }
  }

  /** A 409 or 404 means the list is out of date: say why, then refresh it. */
  private async failed(error: unknown, explanation: string | null): Promise<void> {
    if (explanation) {
      this.show({ tone: 'warn', text: explanation }, '#reports-outcome');
      await this.refresh();
      return;
    }
    const status = error instanceof HttpErrorResponse ? error.status : -1;
    this.show({ tone: 'danger', text: messageFor(status, problemCode(error)) }, '#reports-outcome');
  }

  private async refresh(): Promise<void> {
    try {
      this.reports.set(await firstValueFrom(this.api.list(this.current())));
    } catch (error) {
      if (!isSessionEnded(error)) {
        this.reports.reload();
      }
    }
  }

  private show(outcome: Outcome, ...focus: string[]): void {
    this.outcome.set(outcome);
    this.announcer.announce(outcome.text);
    focusAfterRender(this.host.nativeElement, this.injector, ...focus, 'h1');
  }
}

function decisionProblem(error: unknown): string | null {
  const status = error instanceof HttpErrorResponse ? error.status : -1;
  const code = problemCode(error);
  if (status === 409 && code === 'EVIDENCE_DELETED') {
    return (
      "This letter's report was deleted after 30 days, so the report can't be confirmed. " +
      `You can still reject it. ${REFRESHED}`
    );
  }
  if (status === 409) {
    return `Someone else decided this report first. ${REFRESHED}`;
  }
  return status === 404 ? `This report no longer exists. ${REFRESHED}` : null;
}

function revertProblem(error: unknown): string | null {
  const status = error instanceof HttpErrorResponse ? error.status : -1;
  if (status === 409 && problemCode(error) === 'NOT_CONFIRMED') {
    return `This marker is no longer confirmed: someone already reverted it. ${REFRESHED}`;
  }
  if (status === 409) {
    return `Someone else changed this marker first. ${REFRESHED}`;
  }
  return status === 404 ? `This marker no longer exists. ${REFRESHED}` : null;
}

function messageFor(status: number, code: string | undefined): string {
  if (status === 428 || code === 'INVALID_IF_MATCH') {
    return "Couldn't send the change. Reload the page and try again.";
  }
  if (status === 403) {
    return 'Only an admin can do this. Reload the page to check your role.';
  }
  if (status === 0) {
    return "Couldn't reach OfferShield. Check your connection and try again.";
  }
  return "Couldn't send the change. Try again.";
}
