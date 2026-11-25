import { HttpErrorResponse } from '@angular/common/http';
import { Component, ElementRef, Injector, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormField, FormRoot, form, maxLength } from '@angular/forms/signals';
import { Observable, firstValueFrom } from 'rxjs';
import { Announcer } from '../../../core/a11y/announcer';
import { focusAfterRender } from '../../../core/a11y/focus';
import { Session } from '../../../core/auth/session';
import { isSessionEnded, problemCode, problemViolations } from '../../../core/http/problem';
import { filledIn, firstError } from '../../../shared/forms/form-errors';
import { AppDatePipe } from '../../../shared/format/en-in';
import { Button } from '../../../shared/ui/button';
import { EmptyState } from '../../../shared/ui/empty-state';
import { Field, fieldDescribedBy } from '../../../shared/ui/field';
import { Skeleton } from '../../../shared/ui/loading';
import { Notice, NoticeTone } from '../../../shared/ui/notice';
import { PageHeader } from '../../../shared/ui/page-header';
import { PendingMember, PendingMembersApi } from './members-api';

interface Outcome {
  readonly tone: NoticeTone;
  readonly text: string;
}

const REASON_REQUIRED = 'Give a reason. The student will see it.';
const STALE = 'Someone else decided this request first. The list has been refreshed.';
const GONE = 'This request is no longer waiting. The list has been refreshed.';

/**
 * /tpo/members (PRD F2, §12 "Pending members"): the TPO's own college's join requests, oldest
 * first. Approve, or reject with a required reason the student sees. Each decision carries the
 * request's version as `If-Match`; a 409 means someone else decided first, so the list is
 * refreshed. The server decides who may act (college from the database, 403/404 otherwise).
 */
@Component({
  selector: 'app-pending-members',
  imports: [
    AppDatePipe,
    Button,
    EmptyState,
    Field,
    FormField,
    FormRoot,
    Notice,
    PageHeader,
    Skeleton,
  ],
  templateUrl: './pending-members.html',
  styleUrl: './pending-members.css',
})
export class PendingMembers {
  private readonly api = inject(PendingMembersApi);
  private readonly session = inject(Session);
  private readonly announcer = inject(Announcer);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  protected readonly collegeName = computed(
    () => this.session.me()?.college?.name ?? 'your college',
  );
  protected readonly members = rxResource({ stream: () => this.api.pending() });
  protected readonly list = computed(() => (this.members.hasValue() ? this.members.value() : []));
  protected readonly countLabel = computed(() => {
    const count = this.list().length;
    return `${count} ${count === 1 ? 'request' : 'requests'} waiting, oldest first.`;
  });

  /** The request whose decision is being sent; every decision button waits meanwhile. */
  protected readonly busyId = signal<string | null>(null);
  protected readonly rejectingId = signal<string | null>(null);
  protected readonly outcome = signal<Outcome | null>(null);
  private readonly serverReasonError = signal<string | null>(null);

  protected readonly reasonModel = signal({ reason: '' });
  protected readonly reasonForm = form(
    this.reasonModel,
    (path) => {
      filledIn(path.reason, REASON_REQUIRED);
      maxLength(path.reason, 500, { message: 'Use 500 characters or fewer.' });
    },
    {
      submission: {
        action: async () => {
          await this.sendReject();
          return undefined;
        },
        onInvalid: () => {
          this.announcer.announce(REASON_REQUIRED);
          focusAfterRender(this.host.nativeElement, this.injector, '#reject-reason');
        },
      },
    },
  );
  protected readonly reasonError = computed(
    () => firstError(this.reasonForm.reason()) ?? this.serverReasonError(),
  );
  protected readonly reasonDescribedBy = computed(() =>
    fieldDescribedBy('reject-reason', { hint: true, error: this.reasonError() !== null }),
  );

  protected approve(member: PendingMember): void {
    void this.decide(
      member,
      this.api.approve(member),
      `Approved ${member.studentName}. They'll get an email.`,
    );
  }

  protected startReject(member: PendingMember): void {
    this.rejectingId.set(member.id);
    this.serverReasonError.set(null);
    this.reasonForm().reset({ reason: '' });
    focusAfterRender(this.host.nativeElement, this.injector, '#reject-reason');
  }

  protected cancelReject(member: PendingMember): void {
    this.rejectingId.set(null);
    focusAfterRender(
      this.host.nativeElement,
      this.injector,
      `#member-${member.id} .member__reject`,
    );
  }

  private async sendReject(): Promise<void> {
    const member = this.list().find((item) => item.id === this.rejectingId());
    if (!member) {
      return;
    }
    await this.decide(
      member,
      this.api.reject(member, this.reasonModel().reason.trim()),
      `Rejected ${member.studentName}'s request. They'll get an email with your reason.`,
    );
  }

  private async decide(
    member: PendingMember,
    call: Observable<unknown>,
    done: string,
  ): Promise<void> {
    if (this.busyId() !== null) {
      return;
    }
    this.busyId.set(member.id);
    this.outcome.set(null);
    this.serverReasonError.set(null);
    try {
      await firstValueFrom(call);
      const list = this.list();
      const index = list.findIndex((item) => item.id === member.id);
      const next = list[index + 1] ?? list[index - 1];
      this.members.set(list.filter((item) => item.id !== member.id));
      this.rejectingId.set(null);
      this.show({ tone: 'success', text: done });
      focusAfterRender(
        this.host.nativeElement,
        this.injector,
        next ? `#member-${next.id}-name` : '.empty-state__heading',
      );
    } catch (error) {
      if (!isSessionEnded(error)) {
        await this.showError(error);
      }
    } finally {
      this.busyId.set(null);
    }
  }

  private async showError(error: unknown): Promise<void> {
    const status = error instanceof HttpErrorResponse ? error.status : -1;
    const code = problemCode(error);
    if (status === 400 && (code === 'REASON_REQUIRED' || reasonViolated(error))) {
      this.serverReasonError.set(
        code === 'REASON_REQUIRED' ? REASON_REQUIRED : 'Use 500 characters or fewer.',
      );
      this.announcer.announce('Check the reason.');
      focusAfterRender(this.host.nativeElement, this.injector, '#reject-reason');
      return;
    }
    if (status === 409 || status === 404) {
      this.show({ tone: 'warn', text: status === 409 ? STALE : GONE });
      focusAfterRender(this.host.nativeElement, this.injector, '#members-outcome');
      await this.refresh();
      return;
    }
    this.show({ tone: 'danger', text: messageFor(status, code) });
    focusAfterRender(this.host.nativeElement, this.injector, '#members-outcome');
  }

  /** Reloads the queue in place, keeping the page as it is while the answer comes. */
  private async refresh(): Promise<void> {
    try {
      const fresh = await firstValueFrom(this.api.pending());
      this.members.set(fresh);
      if (!fresh.some((item) => item.id === this.rejectingId())) {
        this.rejectingId.set(null);
      }
    } catch (error) {
      if (!isSessionEnded(error)) {
        this.members.reload();
      }
    }
  }

  private show(outcome: Outcome): void {
    this.outcome.set(outcome);
    this.announcer.announce(outcome.text);
  }
}

function reasonViolated(error: unknown): boolean {
  return problemViolations(error).some((violation) => violation.field === 'reason');
}

function messageFor(status: number, code: string | undefined): string {
  if (status === 428 || code === 'INVALID_IF_MATCH') {
    return "Couldn't send the decision. Reload the page and try again.";
  }
  if (status === 403) {
    return "You can't decide requests for this college. Reload the page to check your role.";
  }
  if (status === 0) {
    return "Couldn't reach OfferShield. Check your connection and try again.";
  }
  return "Couldn't send the decision. Try again.";
}
