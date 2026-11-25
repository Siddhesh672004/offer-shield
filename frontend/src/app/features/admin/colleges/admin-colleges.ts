import { HttpErrorResponse } from '@angular/common/http';
import { Component, ElementRef, Injector, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormField, FormRoot, form, maxLength, required, validate } from '@angular/forms/signals';
import { firstValueFrom } from 'rxjs';
import { Announcer } from '../../../core/a11y/announcer';
import { focusAfterRender } from '../../../core/a11y/focus';
import { isSessionEnded, problemDetail, problemViolations } from '../../../core/http/problem';
import { filledIn, firstError } from '../../../shared/forms/form-errors';
import { Badge } from '../../../shared/ui/badge';
import { Button } from '../../../shared/ui/button';
import { EmptyState } from '../../../shared/ui/empty-state';
import { Field, fieldDescribedBy } from '../../../shared/ui/field';
import { Skeleton } from '../../../shared/ui/loading';
import { Notice, NoticeTone } from '../../../shared/ui/notice';
import { PageHeader } from '../../../shared/ui/page-header';
import { AdminCollegesApi } from './colleges-api';

interface Outcome {
  readonly tone: NoticeTone;
  readonly text: string;
}

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const NAME_TAKEN = 'This college is already on the list.';
const EMAIL_INVALID = 'Enter an email address, like name@college.example.';

/**
 * /admin/colleges (PRD §12 "Colleges and TPOs", P3-01): every college with its TPOs, shown as
 * assigned now or waiting for their first Google sign-in; add a college; assign a TPO by email.
 * The server decides who may do this (ADMIN only) and what an assignment does.
 */
@Component({
  selector: 'app-admin-colleges',
  imports: [Badge, Button, EmptyState, Field, FormField, FormRoot, Notice, PageHeader, Skeleton],
  templateUrl: './admin-colleges.html',
  styleUrl: './admin-colleges.css',
})
export class AdminColleges {
  private readonly api = inject(AdminCollegesApi);
  private readonly announcer = inject(Announcer);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  protected readonly colleges = rxResource({ stream: () => this.api.list() });
  protected readonly list = computed(() => (this.colleges.hasValue() ? this.colleges.value() : []));

  protected readonly adding = signal(false);
  protected readonly addOutcome = signal<Outcome | null>(null);
  private readonly serverNameError = signal<string | null>(null);
  protected readonly collegeModel = signal({ name: '', city: '' });
  protected readonly collegeForm = form(
    this.collegeModel,
    (path) => {
      filledIn(path.name, 'Enter the name of the college.');
      validate(path.name, ({ value }) => {
        const length = value().trim().length;
        if (length === 1) {
          return { kind: 'minLength', message: 'Use at least 2 characters.' };
        }
        return length > 200
          ? { kind: 'maxLength', message: 'Use 200 characters or fewer.' }
          : undefined;
      });
      maxLength(path.city, 100, { message: 'Use 100 characters or fewer.' });
    },
    {
      submission: {
        action: async () => {
          await this.addCollege();
          return undefined;
        },
        onInvalid: () => this.invalid(this.nameError() ? '#college-name' : '#college-city'),
      },
    },
  );
  protected readonly nameError = computed(
    () => firstError(this.collegeForm.name()) ?? this.serverNameError(),
  );
  protected readonly cityError = computed(() => firstError(this.collegeForm.city()));

  protected readonly assigning = signal(false);
  protected readonly assignOutcome = signal<Outcome | null>(null);
  private readonly serverEmailError = signal<string | null>(null);
  protected readonly assignModel = signal({ collegeId: '', email: '' });
  protected readonly assignForm = form(
    this.assignModel,
    (path) => {
      required(path.collegeId, { message: 'Choose a college.' });
      filledIn(path.email, "Enter the TPO's email address.");
      validate(path.email, ({ value }) => {
        const email = value().trim();
        return email !== '' && (!EMAIL.test(email) || email.length > 320)
          ? { kind: 'email', message: EMAIL_INVALID }
          : undefined;
      });
    },
    {
      submission: {
        action: async () => {
          await this.assignTpo();
          return undefined;
        },
        onInvalid: () =>
          this.invalid(this.assignCollegeError() ? '#assign-college' : '#assign-email'),
      },
    },
  );
  protected readonly assignCollegeError = computed(() => firstError(this.assignForm.collegeId()));
  protected readonly emailError = computed(
    () => firstError(this.assignForm.email()) ?? this.serverEmailError(),
  );

  protected describedBy(fieldId: string, hint: boolean, error: string | null): string | null {
    return fieldDescribedBy(fieldId, { hint, error: error !== null });
  }

  private async addCollege(): Promise<void> {
    if (this.adding()) {
      return;
    }
    this.adding.set(true);
    this.addOutcome.set(null);
    this.serverNameError.set(null);
    const name = this.collegeModel().name.trim();
    const city = this.collegeModel().city.trim() || null;
    try {
      const created = await firstValueFrom(this.api.create(name, city));
      this.collegeForm().reset({ name: '', city: '' });
      await this.refresh();
      this.assignModel.update((model) => ({ ...model, collegeId: created.id }));
      this.showAdd({
        tone: 'success',
        text: `Added ${created.name}. It's chosen in “Assign a TPO”.`,
      });
    } catch (error) {
      if (!isSessionEnded(error)) {
        this.addFailed(error);
      }
    } finally {
      this.adding.set(false);
    }
  }

  private addFailed(error: unknown): void {
    const status = error instanceof HttpErrorResponse ? error.status : -1;
    const nameViolated = problemViolations(error).some((v) => v.field === 'name');
    if (status === 409 || nameViolated) {
      this.serverNameError.set(status === 409 ? NAME_TAKEN : 'Use 2 to 200 characters.');
      this.invalid('#college-name');
      return;
    }
    this.showAdd({ tone: 'danger', text: failure(status, "Couldn't add the college. Try again.") });
  }

  private async assignTpo(): Promise<void> {
    if (this.assigning()) {
      return;
    }
    this.assigning.set(true);
    this.assignOutcome.set(null);
    this.serverEmailError.set(null);
    const collegeId = this.assignModel().collegeId;
    const collegeName = this.list().find((item) => item.id === collegeId)?.name ?? 'the college';
    try {
      const result = await firstValueFrom(
        this.api.assignTpo(collegeId, this.assignModel().email.trim()),
      );
      this.assignForm.email().reset('');
      await this.refresh();
      this.showAssign({
        tone: 'success',
        text:
          result.status === 'ASSIGNED'
            ? `${result.email} is now the TPO for ${collegeName}.`
            : `${result.email} becomes the TPO for ${collegeName} at their first Google sign-in.`,
      });
    } catch (error) {
      if (!isSessionEnded(error)) {
        await this.assignFailed(error);
      }
    } finally {
      this.assigning.set(false);
    }
  }

  private async assignFailed(error: unknown): Promise<void> {
    const status = error instanceof HttpErrorResponse ? error.status : -1;
    if (status === 400 && problemViolations(error).some((v) => v.field === 'email')) {
      this.serverEmailError.set(EMAIL_INVALID);
      this.invalid('#assign-email');
      return;
    }
    if (status === 404) {
      this.assignModel.update((model) => ({ ...model, collegeId: '' }));
      this.showAssign({
        tone: 'warn',
        text: 'This college no longer exists. The list has been refreshed.',
      });
      await this.refresh();
      return;
    }
    const fallback = "Couldn't assign the TPO. Try again.";
    this.showAssign({
      tone: status === 409 ? 'warn' : 'danger',
      text: status === 409 ? problemDetail(error, fallback) : failure(status, fallback),
    });
  }

  private async refresh(): Promise<void> {
    try {
      this.colleges.set(await firstValueFrom(this.api.list()));
    } catch (error) {
      if (!isSessionEnded(error)) {
        this.colleges.reload();
      }
    }
  }

  private showAdd(outcome: Outcome): void {
    this.addOutcome.set(outcome);
    this.announcer.announce(outcome.text);
    focusAfterRender(this.host.nativeElement, this.injector, '#college-outcome');
  }

  private showAssign(outcome: Outcome): void {
    this.assignOutcome.set(outcome);
    this.announcer.announce(outcome.text);
    focusAfterRender(this.host.nativeElement, this.injector, '#assign-outcome');
  }

  private invalid(selector: string): void {
    this.announcer.announce('Check the details marked below.');
    focusAfterRender(this.host.nativeElement, this.injector, selector);
  }
}

function failure(status: number, fallback: string): string {
  if (status === 403) {
    return 'Only an admin can do this. Reload the page to check your role.';
  }
  if (status === 0) {
    return "Couldn't reach OfferShield. Check your connection and try again.";
  }
  return fallback;
}
