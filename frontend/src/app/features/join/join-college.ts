import { HttpErrorResponse } from '@angular/common/http';
import {
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  inject,
  signal,
} from '@angular/core';
import {
  FieldState,
  FormField,
  FormRoot,
  SchemaPath,
  form,
  maxLength,
  required,
  validate,
} from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Announcer } from '../../core/a11y/announcer';
import { Session } from '../../core/auth/session';
import { problemCode, problemDetail } from '../../core/http/problem';
import { Notice } from '../../shared/ui/notice';
import { CollegeOption, Membership, MembershipApi } from './membership-api';

const STATUS_LABELS: Record<Membership['status'], string> = {
  PENDING: 'Pending',
  APPROVED: 'Approved',
  REJECTED: 'Not approved',
};

/** `required` plus "not only spaces", with one message for both. */
function filledIn(path: SchemaPath<string>, message: string): void {
  required(path, { message });
  validate(path, ({ value }) =>
    value().trim() === '' ? { kind: 'required', message } : undefined,
  );
}

function firstError(state: FieldState<string>): string | null {
  const errors = state.errors();
  return state.touched() && errors.length > 0 ? (errors[0].message ?? 'Check this field.') : null;
}

/**
 * /join: pick a college, give roll number and department, and send a request the TPO
 * approves or rejects (D-17). A student has one request at a time; after a rejection they
 * may send a new one (D-33.3).
 */
@Component({
  selector: 'app-join-college',
  imports: [FormField, FormRoot, Notice, RouterLink],
  templateUrl: './join-college.html',
  styleUrl: './join-college.css',
})
export class JoinCollege {
  private readonly api = inject(MembershipApi);
  private readonly session = inject(Session);
  private readonly announcer = inject(Announcer);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  protected readonly statusLabels = STATUS_LABELS;
  protected readonly loading = signal(true);
  protected readonly membership = signal<Membership | null>(null);
  protected readonly alreadyRequested = signal(false);
  protected readonly showForm = computed(
    () => !this.loading() && (this.membership()?.status ?? 'REJECTED') === 'REJECTED',
  );

  protected readonly colleges = signal<CollegeOption[]>([]);
  protected readonly searchedFor = signal('');
  protected readonly searching = signal(false);
  protected readonly collegesFailed = signal(false);
  protected readonly sendError = signal<string | null>(null);

  protected readonly searchModel = signal({ query: '' });
  protected readonly searchForm = form(this.searchModel, {
    submission: {
      action: async () => {
        await this.loadColleges(this.searchModel().query);
        return undefined;
      },
    },
  });

  protected readonly joinModel = signal({ collegeId: '', rollNo: '', department: '' });
  protected readonly joinForm = form(
    this.joinModel,
    (path) => {
      filledIn(path.collegeId, 'Choose your college.');
      filledIn(path.rollNo, 'Enter your roll number.');
      maxLength(path.rollNo, 50, { message: 'Use 50 characters or fewer.' });
      filledIn(path.department, 'Enter your department.');
      maxLength(path.department, 100, { message: 'Use 100 characters or fewer.' });
    },
    {
      submission: {
        action: async () => {
          await this.send();
          return undefined;
        },
        onInvalid: () => {
          this.announcer.announce('Check the details marked below.');
          afterNextRender(() => this.focusFirstProblem(), { injector: this.injector });
        },
      },
    },
  );

  protected readonly collegeError = computed(() => firstError(this.joinForm.collegeId()));
  protected readonly rollNoError = computed(() => firstError(this.joinForm.rollNo()));
  protected readonly departmentError = computed(() => firstError(this.joinForm.department()));

  constructor() {
    void this.loadMembership();
    void this.loadColleges('');
  }

  private async loadMembership(): Promise<void> {
    try {
      this.membership.set(await firstValueFrom(this.api.myMembership()));
    } catch {
      // Show the form; if a request already exists, sending it answers 409 and we show that.
    } finally {
      this.loading.set(false);
    }
  }

  private async loadColleges(query: string): Promise<void> {
    const q = query.trim();
    this.searching.set(true);
    this.collegesFailed.set(false);
    try {
      const found = await firstValueFrom(this.api.searchColleges(q));
      this.colleges.set(found);
      this.searchedFor.set(q);
      if (!found.some((college) => college.id === this.joinModel().collegeId)) {
        this.joinModel.update((model) => ({ ...model, collegeId: '' }));
      }
      if (q) {
        this.announcer.announce(
          found.length === 0
            ? `No college matches “${q}”.`
            : `${found.length} ${found.length === 1 ? 'college' : 'colleges'} found.`,
        );
      }
    } catch {
      this.colleges.set([]);
      this.collegesFailed.set(true);
    } finally {
      this.searching.set(false);
    }
  }

  private async send(): Promise<void> {
    this.sendError.set(null);
    const { collegeId, rollNo, department } = this.joinModel();
    try {
      const created = await firstValueFrom(
        this.api.requestToJoin({ collegeId, rollNo: rollNo.trim(), department: department.trim() }),
      );
      this.membership.set(created);
      this.alreadyRequested.set(false);
      this.announcer.announce('Join request sent. Status: pending.');
      void this.session.refresh();
    } catch (error) {
      if (problemCode(error) === 'MEMBERSHIP_EXISTS') {
        await this.showExistingRequest();
      } else {
        this.fail(messageFor(error));
      }
    }
  }

  private async showExistingRequest(): Promise<void> {
    try {
      const existing = await firstValueFrom(this.api.myMembership());
      if (!existing) {
        this.fail(GENERIC_FAILURE);
        return;
      }
      this.membership.set(existing);
      this.alreadyRequested.set(true);
      this.announcer.announce('You already have a join request.');
      void this.session.refresh();
    } catch {
      this.fail(GENERIC_FAILURE);
    }
  }

  /** After a rejected submit, put focus on the first field that needs attention. */
  private focusFirstProblem(): void {
    const root = this.host.nativeElement;
    let target: HTMLElement | null = null;
    if (this.collegeError()) {
      target =
        root.querySelector<HTMLElement>('.colleges input[type="radio"]') ??
        root.querySelector<HTMLElement>('#college-search');
    } else if (this.rollNoError()) {
      target = root.querySelector<HTMLElement>('#roll-no');
    } else if (this.departmentError()) {
      target = root.querySelector<HTMLElement>('#department');
    }
    target?.focus();
  }

  private fail(message: string): void {
    this.sendError.set(message);
    this.announcer.announce(message);
  }
}

const GENERIC_FAILURE = "Couldn't send your request. Try again.";

function messageFor(error: unknown): string {
  if (problemCode(error) === 'EMAIL_NOT_VERIFIED') {
    return (
      "Google hasn't confirmed the email address on your account, so you can't join a college yet. " +
      'Confirm it in your Google account, then sign out and sign in again.'
    );
  }
  if (error instanceof HttpErrorResponse) {
    if (error.status === 404) {
      return "That college isn't available any more. Search again and choose another.";
    }
    if (error.status === 0) {
      return "Couldn't reach OfferShield. Check your connection and try again.";
    }
    if (error.status === 400) {
      return problemDetail(error, 'Check the details and try again.');
    }
  }
  return GENERIC_FAILURE;
}
