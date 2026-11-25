import { HttpErrorResponse } from '@angular/common/http';
import {
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  inject,
  linkedSignal,
  signal,
} from '@angular/core';
import { FieldState, FormField, FormRoot, form, validate } from '@angular/forms/signals';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Announcer } from '../../../core/a11y/announcer';
import {
  isSessionEnded,
  problemCode,
  problemDetail,
  problemViolations,
  retryAfterSeconds,
} from '../../../core/http/problem';
import { formatNumber, formatWait } from '../../../shared/format/en-in';
import { Button } from '../../../shared/ui/button';
import { Field, fieldDescribedBy } from '../../../shared/ui/field';
import { Notice } from '../../../shared/ui/notice';
import { PageHeader } from '../../../shared/ui/page-header';
import { SubmissionAccepted, SubmissionApi, newIdempotencyKey } from '../submission-api';

type Mode = 'pdf' | 'text';
type DetailField = 'text' | 'senderEmail' | 'recruiterPhone' | 'upiId';

/** PRD F3 / D-33.1 limits, checked here first so the user hears about them at once. */
const MAX_PDF_BYTES = 5 * 1024 * 1024;
const MAX_TEXT_CHARS = 20_000;

/** The API's own patterns (`SubmissionForm`), so the browser and the server agree. */
const EMAIL = /^[^\s@]{1,64}@[^\s@]+\.[^\s@]{2,}$/;
const PHONE = /^\+?[0-9][0-9 ()-]{5,30}$/;
const UPI_ID = /^[A-Za-z0-9._-]{2,64}@[A-Za-z][A-Za-z0-9.-]{1,34}$/;

const FIELD_MESSAGES: Readonly<Record<DetailField, string>> = {
  text: 'Use 20,000 characters or fewer. Paste only the letter.',
  senderEmail: 'Enter an email address like hr@company.com.',
  recruiterPhone: 'Enter a phone number using digits, spaces, brackets or dashes.',
  upiId: 'Enter a UPI ID like name@bank.',
};

/** Errors about the PDF itself (413, 415, 422): shown under the file field. */
const FILE_MESSAGES: Readonly<Record<string, string>> = {
  FILE_TOO_LARGE: 'This file is larger than 5 MB. Choose a smaller PDF, or paste the text instead.',
  UNSUPPORTED_FILE_TYPE: "This file isn't a PDF. Choose a PDF, or paste the text instead.",
  EMPTY_FILE: 'This file is empty. Choose another PDF.',
  TOO_MANY_PAGES:
    'This PDF has more than 10 pages. Upload only the offer letter, or paste its text instead.',
  TEXT_TOO_SHORT:
    "We couldn't read enough text from this PDF (it may be a scan). Paste the text instead.",
  PDF_ENCRYPTED: 'This PDF is password-protected. Paste the text instead.',
  PDF_UNREADABLE: "We couldn't read this PDF. Paste the text instead.",
  PDF_TIMEOUT: 'Reading this PDF took too long. Paste the text instead.',
  PDF_TOO_COMPLEX: 'This PDF is too large or complex to read. Paste the text instead.',
  TEXT_TOO_LONG:
    "This PDF has more text than an offer letter (over 50,000 characters). Paste only the letter's text instead.",
};

const NETWORK = "Couldn't reach OfferShield. Check your connection and try again.";
const GENERIC = "Couldn't send your letter. Try again.";

/** A server-side field error, shown while the field still holds the value it was about. */
interface ServerFieldError {
  readonly message: string;
  readonly value: string;
}

/** The report page of a sent letter (a same-origin path). */
function reportPath(submissionId: string): string {
  return `/app/submissions/${encodeURIComponent(submissionId)}`;
}

function optional(pattern: RegExp, maxLength: number, message: string) {
  return ({ value }: { value: () => string }) => {
    const trimmed = value().trim();
    return trimmed !== '' && (trimmed.length > maxLength || !pattern.test(trimmed))
      ? { kind: 'pattern', message }
      : undefined;
  };
}

function touchedError(state: FieldState<string>): string | null {
  const errors = state.errors();
  return state.touched() && errors.length > 0 ? (errors[0].message ?? 'Check this field.') : null;
}

/**
 * /app/upload (PRD F3): a PDF (≤ 5 MB, ≤ 10 pages) or pasted text (≤ 20,000 characters), plus an
 * optional sender email, recruiter phone and UPI ID. One `Idempotency-Key` per letter: a retry or
 * a double click of the same letter reuses it, so the server never makes a second submission;
 * changing the letter or a detail starts a new one. Every error of `POST /api/submissions` gets a
 * plain message; file problems appear under the file field with "Paste the text instead".
 */
@Component({
  selector: 'app-upload',
  imports: [Button, Field, FormField, FormRoot, Notice, PageHeader],
  templateUrl: './upload.html',
  styleUrl: './upload.css',
})
export class Upload {
  private readonly api = inject(SubmissionApi);
  private readonly router = inject(Router);
  private readonly announcer = inject(Announcer);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  /** The last letter sent and its key: reused only while the letter is unchanged. */
  private attempt: { readonly fingerprint: string; readonly key: string } | null = null;

  protected readonly maxTextLabel = formatNumber(MAX_TEXT_CHARS);
  protected readonly model = signal({
    mode: 'pdf' as Mode,
    text: '',
    senderEmail: '',
    recruiterPhone: '',
    upiId: '',
  });
  protected readonly mode = computed(() => this.model().mode);
  /** Reset whenever the mode changes, because the file input is re-created empty. */
  protected readonly file = linkedSignal<Mode, File | null>({
    source: this.mode,
    computation: () => null,
  });
  protected readonly fileError = linkedSignal<Mode, string | null>({
    source: this.mode,
    computation: () => null,
  });
  protected readonly sending = signal(false);
  protected readonly sendError = signal<string | null>(null);
  /** Sent, but its report page didn't open: link to it. */
  protected readonly sentId = signal<string | null>(null);
  protected readonly sentLink = computed(() => {
    const id = this.sentId();
    return id === null ? null : reportPath(id);
  });
  private readonly serverErrors = signal<Partial<Record<DetailField, ServerFieldError>>>({});

  protected readonly uploadForm = form(
    this.model,
    (path) => {
      validate(path.text, ({ value, valueOf }) => {
        if (valueOf(path.mode) !== 'text') {
          return undefined;
        }
        if (value().trim() === '') {
          return { kind: 'required', message: 'Paste the text of the offer letter.' };
        }
        return value().length > MAX_TEXT_CHARS
          ? { kind: 'maxLength', message: FIELD_MESSAGES.text }
          : undefined;
      });
      validate(path.senderEmail, optional(EMAIL, 254, FIELD_MESSAGES.senderEmail));
      validate(path.recruiterPhone, optional(PHONE, 32, FIELD_MESSAGES.recruiterPhone));
      validate(path.upiId, optional(UPI_ID, 100, FIELD_MESSAGES.upiId));
    },
    {
      submission: {
        action: async () => {
          await this.send();
          return undefined;
        },
        onInvalid: () => {
          this.checkFile();
          this.announcer.announce('Check the details marked below.');
          this.focusFirstProblem();
        },
      },
    },
  );

  protected readonly textLength = computed(() => formatNumber(this.model().text.length));
  protected readonly textError = computed(() => this.errorFor('text', this.uploadForm.text()));
  protected readonly emailError = computed(() =>
    this.errorFor('senderEmail', this.uploadForm.senderEmail()),
  );
  protected readonly phoneError = computed(() =>
    this.errorFor('recruiterPhone', this.uploadForm.recruiterPhone()),
  );
  protected readonly upiError = computed(() => this.errorFor('upiId', this.uploadForm.upiId()));

  protected describedBy(fieldId: string, hint: boolean, error: string | null): string | null {
    return fieldDescribedBy(fieldId, { hint, error: error !== null });
  }

  protected onFileChosen(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.file.set(input.files?.[0] ?? null);
    this.fileError.set(null);
    this.sendError.set(null);
    this.checkFile();
  }

  /** After a PDF problem: switch to pasting and put the cursor in the text box. */
  protected pasteInstead(): void {
    this.model.update((model) => ({ ...model, mode: 'text' }));
    this.sendError.set(null);
    afterNextRender(() => this.query('#letter-text')?.focus(), { injector: this.injector });
  }

  private async send(): Promise<void> {
    if (this.sending()) {
      return;
    }
    if (!this.checkFile()) {
      this.announcer.announce(this.fileError() ?? 'Check the details marked below.');
      this.focusFirstProblem();
      return;
    }
    const { mode, text, senderEmail, recruiterPhone, upiId } = this.model();
    const letter = {
      file: mode === 'pdf' ? this.file() : null,
      text: mode === 'text' ? text : null,
      senderEmail,
      recruiterPhone,
      upiId,
    };
    const fingerprint = this.fingerprint();
    if (this.attempt?.fingerprint !== fingerprint) {
      this.attempt = { fingerprint, key: newIdempotencyKey() };
    }

    this.sending.set(true);
    this.sendError.set(null);
    this.sentId.set(null);
    this.serverErrors.set({});
    let accepted: SubmissionAccepted;
    try {
      accepted = await firstValueFrom(this.api.submit(letter, this.attempt.key));
    } catch (error) {
      this.sending.set(false);
      if (!isSessionEnded(error)) {
        this.showError(error);
      }
      return;
    }
    this.announcer.announce('Letter sent. Checking your letter now.');
    await this.openReport(accepted.submissionId);
    this.sending.set(false);
  }

  /**
   * The letter is already sent, so a failed navigation (a lazy chunk missing after a redeploy, a
   * cancelled navigation) must not say "Couldn't send": show a plain link to its report instead.
   */
  private async openReport(submissionId: string): Promise<void> {
    let opened = false;
    try {
      opened = (await this.router.navigateByUrl(reportPath(submissionId))) === true;
    } catch {
      opened = false;
    }
    if (!opened) {
      this.sentId.set(submissionId);
      this.announcer.announce("Letter sent, but its report didn't open. Use the link to open it.");
      afterNextRender(() => this.query('.upload-sent a')?.focus(), { injector: this.injector });
    }
  }

  /** In PDF mode a file must be chosen, be a PDF by name or type, and be at most 5 MB. */
  private checkFile(): boolean {
    if (this.mode() !== 'pdf') {
      return true;
    }
    const file = this.file();
    let message: string | null = null;
    if (!file) {
      message = 'Choose the PDF of the offer letter.';
    } else if (file.size > MAX_PDF_BYTES) {
      message = FILE_MESSAGES['FILE_TOO_LARGE'];
    } else if (file.type !== 'application/pdf' && !file.name.toLowerCase().endsWith('.pdf')) {
      message = FILE_MESSAGES['UNSUPPORTED_FILE_TYPE'];
    }
    this.fileError.set(message);
    return message === null;
  }

  private showError(error: unknown): void {
    const code = problemCode(error);
    const status = error instanceof HttpErrorResponse ? error.status : -1;

    const fileMessage = this.fileMessageFor(error, status, code);
    if (fileMessage && this.mode() === 'pdf') {
      this.fileError.set(fileMessage);
      this.announcer.announce(fileMessage);
      this.focusFirstProblem();
      return;
    }

    if (code === 'VALIDATION_FAILED' && this.showFieldErrors(error)) {
      this.announcer.announce('Check the details marked below.');
      this.focusFirstProblem();
      return;
    }

    const message = fileMessage ?? this.messageFor(error, status, code);
    this.sendError.set(message);
    this.announcer.announce(message);
  }

  /** 413, 415 and the PDF rules' 422s are about the file; null for anything else. */
  private fileMessageFor(error: unknown, status: number, code: string | undefined): string | null {
    if (code && code in FILE_MESSAGES) {
      return FILE_MESSAGES[code];
    }
    if (status === 413) {
      return FILE_MESSAGES['FILE_TOO_LARGE'];
    }
    if (status === 415) {
      return FILE_MESSAGES['UNSUPPORTED_FILE_TYPE'];
    }
    if (status === 422 && code !== 'IDEMPOTENCY_KEY_REUSED') {
      return problemDetail(error, FILE_MESSAGES['PDF_UNREADABLE']);
    }
    return null;
  }

  private messageFor(error: unknown, status: number, code: string | undefined): string {
    switch (code) {
      case 'UPLOAD_LIMIT':
        return `You can check up to 5 letters every 10 minutes. Try again in ${this.wait(error)}.`;
      case 'UPLOAD_ATTEMPT_LIMIT':
        // PDFs that failed to read count separately (P13); pasted text is still accepted meanwhile.
        return `Too many letters that couldn't be read. Try again in ${this.wait(error)}, or paste the letter's text instead.`;
      case 'RATE_LIMITED':
        return `Too many requests at once. Try again in ${this.wait(error)}.`;
      case 'SERVER_BUSY':
        return 'OfferShield is busy reading other files. Try again in a minute.';
      case 'UPLOAD_IN_PROGRESS':
        return 'Another letter is still being read. Wait for it to finish.';
      case 'STORAGE_UNAVAILABLE':
        return 'The upload failed. Try again.';
      case 'CSRF_INVALID':
        return 'This page needs a refresh. Reload it and try again.';
      case 'FORBIDDEN':
        return 'Only student accounts can check offer letters.';
      case 'FILE_OR_TEXT':
        return "Add a PDF, or paste the letter's text.";
      case 'UPLOAD_INCOMPLETE':
      case 'MULTIPART_INVALID':
        return "The upload didn't finish. Check your connection and try again.";
      case 'IDEMPOTENCY_KEY_REUSED':
      case 'IDEMPOTENCY_KEY_REQUIRED':
      case 'INVALID_IDEMPOTENCY_KEY':
        this.attempt = null;
        return 'Something changed since your last try. Press “Check this letter” again.';
    }
    if (status === 0) {
      return NETWORK;
    }
    if (status === 429) {
      return `Too many requests at once. Try again in ${this.wait(error)}.`;
    }
    if (status >= 500) {
      return 'Something went wrong on our side. Try again in a minute.';
    }
    return status === 400 ? problemDetail(error, GENERIC) : GENERIC;
  }

  private wait(error: unknown): string {
    const seconds = retryAfterSeconds(error);
    return seconds === null ? 'a few minutes' : formatWait(seconds);
  }

  /** Maps 400 `VALIDATION_FAILED` violations onto our fields; false when none is ours. */
  private showFieldErrors(error: unknown): boolean {
    const model = this.model();
    const errors: Partial<Record<DetailField, ServerFieldError>> = {};
    for (const violation of problemViolations(error)) {
      const field = violation.field as DetailField;
      if (field in FIELD_MESSAGES) {
        errors[field] = { message: FIELD_MESSAGES[field], value: model[field] };
      }
    }
    this.serverErrors.set(errors);
    return Object.keys(errors).length > 0;
  }

  private errorFor(field: DetailField, state: FieldState<string>): string | null {
    const local = touchedError(state);
    if (local) {
      return local;
    }
    const server = this.serverErrors()[field];
    return server && server.value === this.model()[field] ? server.message : null;
  }

  private fingerprint(): string {
    const { mode, text, senderEmail, recruiterPhone, upiId } = this.model();
    const file = this.file();
    const content = mode === 'pdf' ? [file?.name, file?.size, file?.lastModified] : [text.trim()];
    return JSON.stringify([mode, content, senderEmail.trim(), recruiterPhone.trim(), upiId.trim()]);
  }

  private focusFirstProblem(): void {
    afterNextRender(
      () => {
        const problems: [string | null, string][] = [
          [this.fileError(), '#letter-file'],
          [this.textError(), '#letter-text'],
          [this.emailError(), '#sender-email'],
          [this.phoneError(), '#recruiter-phone'],
          [this.upiError(), '#upi-id'],
        ];
        const first = problems.find(([error]) => error !== null);
        if (first) {
          this.query(first[1])?.focus();
        }
      },
      { injector: this.injector },
    );
  }

  private query(selector: string): HTMLElement | null {
    return this.host.nativeElement.querySelector<HTMLElement>(selector);
  }
}
