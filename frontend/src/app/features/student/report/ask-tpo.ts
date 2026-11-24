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
import { firstValueFrom } from 'rxjs';
import { Announcer } from '../../../core/a11y/announcer';
import { isSessionEnded, problemCode, problemDetail } from '../../../core/http/problem';
import { AppDatePipe } from '../../../shared/format/en-in';
import { Badge, BadgeIcon, BadgeTone } from '../../../shared/ui/badge';
import { Button } from '../../../shared/ui/button';
import { ReviewApi, ReviewCase, ReviewStatus } from '../review-api';

const REPORT_NOT_READY = 'This report is still being prepared. Try again in a minute.';

interface StatusView {
  readonly label: string;
  readonly tone: BadgeTone;
  readonly icon: BadgeIcon;
  readonly text: string;
}

/** How a case's state reads to the student (same wording as the decision email, ReviewEmails). */
export const REVIEW_STATUS: Readonly<Record<ReviewStatus, StatusView>> = {
  OPEN: {
    label: 'Waiting for your TPO',
    tone: 'warn',
    icon: 'clock',
    text: "Your TPO will review this report. You'll get an email when they decide.",
  },
  CONFIRMED_SCAM: {
    label: 'Marked as a scam',
    tone: 'danger',
    icon: 'cross',
    text: 'Your TPO marked this letter as a scam. Do not pay anything or share your documents.',
  },
  CONFIRMED_GENUINE: {
    label: 'Marked as genuine',
    tone: 'info',
    icon: 'check',
    text:
      'Your TPO marked this letter as genuine. Still, never pay money to get a job, and check ' +
      'any change of details with the company directly.',
  },
  INCONCLUSIVE: {
    label: 'No conclusion',
    tone: 'neutral',
    icon: 'none',
    text:
      "Your TPO couldn't reach a conclusion. Check the offer through the company's official " +
      'website or careers page before you act.',
  },
};

/**
 * "Ask my TPO" (PRD F5, §6.5) for one letter. Shows the student's existing case for it (state,
 * the TPO's note as plain text); otherwise, when `canAsk` (an approved member, and the letter was
 * checked on campus), a button that opens a case. A 409 shows the existing case instead.
 */
@Component({
  selector: 'app-ask-tpo',
  imports: [AppDatePipe, Badge, Button],
  templateUrl: './ask-tpo.html',
  styles: `
    .ask-tpo p {
      color: var(--color-text-secondary);
    }
    .ask-tpo__note-label {
      margin-bottom: var(--space-1);
      font-weight: var(--weight-semibold);
    }
    .ask-tpo__note {
      margin: 0 0 var(--space-4);
      padding: var(--space-3) var(--space-4);
      border-left: 3px solid var(--color-border-strong);
      border-radius: 0 var(--radius-sm) var(--radius-sm) 0;
      background: var(--color-surface-muted);
    }
  `,
})
export class AskTpo {
  readonly submissionId = input.required<string>();
  /** An approved member asking about a letter checked while on campus (the API checks again). */
  readonly canAsk = input(false);

  private readonly api = inject(ReviewApi);
  private readonly announcer = inject(Announcer);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  protected readonly statuses = REVIEW_STATUS;
  protected readonly mine = rxResource({ stream: () => this.api.mine() });
  private readonly created = signal<ReviewCase | null>(null);
  protected readonly current = computed(
    () =>
      this.created() ??
      (this.mine.hasValue()
        ? (this.mine.value().find((item) => item.submissionId === this.submissionId()) ?? null)
        : null),
  );
  /** The student's cases didn't load, so we can't tell whether this letter has one. */
  protected readonly mineFailed = computed(() => {
    const error = this.mine.error();
    return error !== undefined && !isSessionEnded(error);
  });
  protected readonly asking = signal(false);
  protected readonly alreadyAsked = signal(false);
  protected readonly error = signal<string | null>(null);
  /** 409 `REPORT_NOT_READY`: the report is still being prepared, so the button says "Try again". */
  protected readonly notReady = signal(false);

  protected async ask(): Promise<void> {
    if (this.asking()) {
      return;
    }
    this.asking.set(true);
    this.error.set(null);
    this.notReady.set(false);
    try {
      this.created.set(await firstValueFrom(this.api.ask(this.submissionId())));
      this.announcer.announce('Sent to your TPO. Status: waiting for your TPO.');
      this.focusHeading();
    } catch (error) {
      if (isSessionEnded(error)) {
        return;
      }
      const code = problemCode(error);
      if (code === 'REVIEW_EXISTS') {
        this.alreadyAsked.set(true);
        this.mine.reload();
        this.announcer.announce('You already asked your TPO about this letter.');
        this.focusHeading();
        return;
      }
      if (code === 'REPORT_NOT_READY') {
        this.notReady.set(true);
      }
      const message =
        code === 'REPORT_NOT_READY'
          ? problemDetail(error, REPORT_NOT_READY)
          : error instanceof HttpErrorResponse && error.status === 404
            ? 'Only an approved member of your college can ask, and only about a letter checked ' +
              'while a member.'
            : "Couldn't send it to your TPO. Try again.";
      this.error.set(message);
      this.announcer.announce(message);
    } finally {
      this.asking.set(false);
    }
  }

  private focusHeading(): void {
    afterNextRender(
      () => this.host.nativeElement.querySelector<HTMLElement>('#ask-tpo-heading')?.focus(),
      { injector: this.injector },
    );
  }
}
