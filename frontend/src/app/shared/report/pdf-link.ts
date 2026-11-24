import { Component, ElementRef, Injector, computed, inject, input, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, firstValueFrom } from 'rxjs';
import { Announcer } from '../../core/a11y/announcer';
import { focusAfterRender } from '../../core/a11y/focus';
import { isSessionEnded, problemCode } from '../../core/http/problem';
import { AppDateTimePipe } from '../format/en-in';
import { Button } from '../ui/button';
import { PdfLink } from './evidence-models';

type LinkState = 'idle' | 'loading' | 'ready' | 'missing' | 'error';

const NO_PDF = 'No PDF stored for this letter. It was pasted as text, or file storage was off.';

/**
 * The original PDF for its case's TPO or the admin (D-05). "Get the PDF link" asks the server
 * for a short-lived signed link, then shows "Open the PDF" (new tab, `rel="noopener
 * noreferrer"`) with its expiry. Two steps, so no popup blocker gets in the way. The link is a
 * credential: it is shown only here and never logged.
 */
@Component({
  selector: 'app-pdf-link',
  imports: [AppDateTimePipe, Button],
  template: `
    @if (!available() || state() === 'missing') {
      <p class="muted">{{ noPdf }}</p>
    } @else {
      @if (link(); as ready) {
        <div class="cluster">
          <a
            appButton
            class="pdf-open"
            variant="secondary"
            size="sm"
            target="_blank"
            rel="noopener noreferrer"
            [href]="ready.url"
            >Open the PDF<span class="visually-hidden"> (opens in a new tab)</span></a
          >
          <button appButton type="button" variant="ghost" size="sm" (click)="get()">
            Get a new link
          </button>
        </div>
        <p class="fine-print">Works until {{ ready.expiresAt | appDateTime }}.</p>
      } @else {
        <button
          appButton
          type="button"
          variant="secondary"
          size="sm"
          [loading]="state() === 'loading'"
          [disabled]="state() === 'loading'"
          (click)="get()"
        >
          Get the PDF link
        </button>
        @if (state() === 'error') {
          <p class="field__error">Couldn't get the PDF link. Try again.</p>
        }
      }
    }
  `,
  styles: `
    :host {
      display: grid;
      gap: var(--space-2);
      justify-items: start;
    }
    p {
      margin: 0;
    }
  `,
})
export class PdfLinkButton {
  /** `pdfAvailable` from the case or report. */
  readonly available = input.required<boolean>();
  /** Asks the server for a fresh link (the review or the scam-report endpoint). */
  readonly fetch = input.required<() => Observable<PdfLink>>();

  private readonly announcer = inject(Announcer);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  protected readonly noPdf = NO_PDF;
  protected readonly state = signal<LinkState>('idle');
  private readonly answer = signal<PdfLink | null>(null);
  protected readonly link = computed(() => (this.state() === 'ready' ? this.answer() : null));

  protected async get(): Promise<void> {
    if (this.state() === 'loading') {
      return;
    }
    this.state.set('loading');
    try {
      this.answer.set(await firstValueFrom(this.fetch()()));
      this.state.set('ready');
      this.announcer.announce('PDF link ready. It opens in a new tab.');
      focusAfterRender(this.host.nativeElement, this.injector, '.pdf-open');
    } catch (error) {
      if (isNotStored(error)) {
        this.state.set('missing');
        this.announcer.announce(NO_PDF);
      } else {
        this.state.set('error');
        if (!isSessionEnded(error)) {
          this.announcer.announce("Couldn't get the PDF link. Try again.");
        }
      }
    }
  }
}

function isNotStored(error: unknown): boolean {
  return (
    error instanceof HttpErrorResponse &&
    error.status === 404 &&
    problemCode(error) === 'PDF_NOT_AVAILABLE'
  );
}
