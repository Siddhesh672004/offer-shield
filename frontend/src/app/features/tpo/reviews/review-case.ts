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
import { FormField, FormRoot, form, maxLength, required } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Announcer } from '../../../core/a11y/announcer';
import { focusAfterRender } from '../../../core/a11y/focus';
import { isSessionEnded, problemCode, problemViolations } from '../../../core/http/problem';
import { firstError } from '../../../shared/forms/form-errors';
import { AppDateTimePipe } from '../../../shared/format/en-in';
import { Evidence } from '../../../shared/report/evidence';
import { NO_MARKERS_RECORDED } from '../../../shared/report/evidence-models';
import { PdfLinkButton } from '../../../shared/report/pdf-link';
import { Badge } from '../../../shared/ui/badge';
import { Button } from '../../../shared/ui/button';
import { Field, fieldDescribedBy } from '../../../shared/ui/field';
import { Skeleton } from '../../../shared/ui/loading';
import { Notice, NoticeTone } from '../../../shared/ui/notice';
import { PageHeader } from '../../../shared/ui/page-header';
import { CASE_STATUS, DECISION_OPTIONS } from './case-status';
import { ReviewDecision, ReviewQueueApi } from './reviews-api';

interface Outcome {
  readonly tone: NoticeTone;
  readonly text: string;
}

const CHOOSE = 'Choose a decision.';
const NOTE_TOO_LONG = 'Use 1,000 characters or fewer.';
const STALE = 'Someone else decided this case first. The case has been refreshed.';
const EVIDENCE_DELETED =
  "This letter's report was deleted after 30 days, so it can't be marked as a scam any more. " +
  'You can still mark it as genuine or as no conclusion.';

/**
 * /tpo/reviews/:id (PRD F5, §6.5, D-04, D-05): one case of the TPO's own college. The evidence
 * (band, signals with their evidence and source kind, registry markers), the original PDF behind
 * a short-lived link, and the decision with an optional note to the student. The decision
 * carries the case's version as `If-Match`; a 409 means another TPO (or tab) decided first, so
 * the case is reloaded and shown as decided.
 */
@Component({
  selector: 'app-review-case',
  imports: [
    AppDateTimePipe,
    Badge,
    Button,
    Evidence,
    Field,
    FormField,
    FormRoot,
    Notice,
    PageHeader,
    PdfLinkButton,
    RouterLink,
    Skeleton,
  ],
  templateUrl: './review-case.html',
  styleUrl: './review-case.css',
})
export class ReviewCase {
  /** The case ID, from the route. */
  readonly id = input.required<string>();

  private readonly api = inject(ReviewQueueApi);
  private readonly announcer = inject(Announcer);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  protected readonly statuses = CASE_STATUS;
  protected readonly options = DECISION_OPTIONS;
  protected readonly reviewCase = rxResource({
    params: () => this.id(),
    stream: ({ params }) => this.api.get(params),
  });
  protected readonly current = computed(() =>
    this.reviewCase.hasValue() ? this.reviewCase.value() : null,
  );
  protected readonly notFound = computed(() => {
    const error = this.reviewCase.error();
    return error instanceof HttpErrorResponse && error.status === 404;
  });
  protected readonly pdfFetch = () => this.api.pdfLink(this.id());

  protected readonly sending = signal(false);
  /** Cleared when another case opens in the same page. */
  protected readonly outcome = linkedSignal<string, Outcome | null>({
    source: this.id,
    computation: () => null,
  });
  private readonly serverNoteError = signal<string | null>(null);

  protected readonly decisionModel = linkedSignal({
    source: this.id,
    computation: () => ({ decision: '', note: '' }),
  });
  protected readonly decisionForm = form(
    this.decisionModel,
    (path) => {
      required(path.decision, { message: CHOOSE });
      maxLength(path.note, 1000, { message: NOTE_TOO_LONG });
    },
    {
      submission: {
        action: async () => {
          await this.send();
          return undefined;
        },
        onInvalid: () => {
          this.announcer.announce('Check the decision marked below.');
          focusAfterRender(
            this.host.nativeElement,
            this.injector,
            this.decisionError() ? '.decision input' : '#decision-note',
          );
        },
      },
    },
  );
  protected readonly decisionError = computed(() => firstError(this.decisionForm.decision()));
  protected readonly noteError = computed(
    () => firstError(this.decisionForm.note()) ?? this.serverNoteError(),
  );
  protected readonly noteDescribedBy = computed(() =>
    fieldDescribedBy('decision-note', { hint: true, error: this.noteError() !== null }),
  );

  private async send(): Promise<void> {
    const reviewCase = this.current();
    if (!reviewCase || this.sending()) {
      return;
    }
    this.sending.set(true);
    this.outcome.set(null);
    this.serverNoteError.set(null);
    const { decision, note } = this.decisionModel();
    try {
      const decided = await firstValueFrom(
        this.api.decide(reviewCase, decision as ReviewDecision, note.trim() || null),
      );
      this.reviewCase.set(decided);
      const sent = `Decision sent: ${CASE_STATUS[decided.status].label}.`;
      // A scam decision that recorded no marker flags no other letter: say so (older answers have no count).
      if (decided.status === 'CONFIRMED_SCAM' && decided.markersRecorded === 0) {
        this.outcome.set({ tone: 'warn', text: NO_MARKERS_RECORDED });
        this.announcer.announce(`${sent} ${NO_MARKERS_RECORDED} The student will get an email.`);
      } else {
        this.announcer.announce(`${sent} The student will get an email.`);
      }
      focusAfterRender(this.host.nativeElement, this.injector, '#decided-heading');
    } catch (error) {
      if (!isSessionEnded(error)) {
        await this.showError(error);
      }
    } finally {
      this.sending.set(false);
    }
  }

  private async showError(error: unknown): Promise<void> {
    const status = error instanceof HttpErrorResponse ? error.status : -1;
    const code = problemCode(error);
    if (status === 409 && code === 'EVIDENCE_DELETED') {
      this.show({ tone: 'warn', text: EVIDENCE_DELETED });
      return;
    }
    if (status === 409) {
      this.show({ tone: 'warn', text: STALE });
      await this.refresh();
      return;
    }
    if (status === 404) {
      this.show({ tone: 'warn', text: 'This case is no longer in your queue.' });
      this.reviewCase.reload();
      return;
    }
    if (status === 400 && problemViolations(error).some((v) => v.field === 'note')) {
      this.serverNoteError.set(NOTE_TOO_LONG);
      this.announcer.announce(NOTE_TOO_LONG);
      focusAfterRender(this.host.nativeElement, this.injector, '#decision-note');
      return;
    }
    this.show({ tone: 'danger', text: messageFor(status, code) });
  }

  private async refresh(): Promise<void> {
    try {
      this.reviewCase.set(await firstValueFrom(this.api.get(this.id())));
    } catch (error) {
      if (!isSessionEnded(error)) {
        this.reviewCase.reload();
      }
    }
  }

  private show(outcome: Outcome): void {
    this.outcome.set(outcome);
    this.announcer.announce(outcome.text);
    focusAfterRender(this.host.nativeElement, this.injector, '#case-outcome');
  }
}

function messageFor(status: number, code: string | undefined): string {
  if (status === 428 || code === 'INVALID_IF_MATCH') {
    return "Couldn't send the decision. Reload the page and try again.";
  }
  if (status === 403) {
    return "You can't decide cases for this college. Reload the page to check your role.";
  }
  if (status === 0) {
    return "Couldn't reach OfferShield. Check your connection and try again.";
  }
  return "Couldn't send the decision. Try again.";
}
