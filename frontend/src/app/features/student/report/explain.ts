import { HttpErrorResponse } from '@angular/common/http';
import {
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  inject,
  input,
  signal,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Announcer } from '../../../core/a11y/announcer';
import { isSessionEnded, problemCode, retryAfterSeconds } from '../../../core/http/problem';
import { formatWait } from '../../../shared/format/en-in';
import { AiTag } from '../../../shared/ui/ai-tag';
import { Button } from '../../../shared/ui/button';
import { Explanation, ReportApi } from '../report-api';

/**
 * "Explain simply" (PRD F4): asks for a plain-language summary of the report. An AI-written one
 * is labelled "AI-written summary"; when the AI is unavailable the API returns a summary built
 * from the checks, labelled as such. The text is shown as text (line breaks kept), never as HTML.
 */
@Component({
  selector: 'app-explain',
  imports: [AiTag, Button],
  template: `
    <section class="card explain" aria-labelledby="explain-heading">
      <div class="card__header">
        <div>
          <h2 id="explain-heading" class="card__title" tabindex="-1">Explain simply</h2>
          <p class="card__description">The report in a few plain sentences.</p>
        </div>
        @if (summary(); as summary) {
          @if (summary.aiWritten) {
            <app-ai-tag>AI-written summary</app-ai-tag>
          } @else {
            <span class="explain__source">Summary from the checks (AI unavailable)</span>
          }
        }
      </div>

      @if (summary(); as summary) {
        <p class="explain__text plain-text">{{ summary.explanation }}</p>
      } @else {
        @if (error(); as error) {
          <p class="field__error explain__error">{{ error }}</p>
        }
        <button
          appButton
          type="button"
          variant="secondary"
          class="explain__button"
          [loading]="loading()"
          [disabled]="loading()"
          (click)="explain()"
        >
          {{ loading() ? 'Writing the summary…' : 'Explain simply' }}
        </button>
      }
    </section>
  `,
  styles: `
    .explain__text {
      margin: 0;
      font-size: var(--text-md);
      line-height: var(--leading-body);
    }
    .explain__source {
      color: var(--color-text-muted);
      font-size: var(--text-sm);
      font-weight: var(--weight-medium);
    }
    .explain__error {
      margin-bottom: var(--space-3);
    }
  `,
})
export class ExplainSummary {
  readonly submissionId = input.required<string>();

  private readonly api = inject(ReportApi);
  private readonly announcer = inject(Announcer);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  protected readonly loading = signal(false);
  protected readonly summary = signal<Explanation | null>(null);
  protected readonly error = signal<string | null>(null);

  protected async explain(): Promise<void> {
    if (this.loading()) {
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    try {
      this.summary.set(await firstValueFrom(this.api.explain(this.submissionId())));
      this.announcer.announce('Summary ready.');
      // The button is gone, so focus the card's heading; the summary follows it.
      afterNextRender(
        () => this.host.nativeElement.querySelector<HTMLElement>('#explain-heading')?.focus(),
        { injector: this.injector },
      );
    } catch (error) {
      if (!isSessionEnded(error)) {
        const message = messageFor(error);
        this.error.set(message);
        this.announcer.announce(message);
      }
    } finally {
      this.loading.set(false);
    }
  }
}

function messageFor(error: unknown): string {
  const status = error instanceof HttpErrorResponse ? error.status : -1;
  if (status === 429) {
    const seconds = retryAfterSeconds(error);
    const wait = seconds === null ? 'a few minutes' : formatWait(seconds);
    return problemCode(error) === 'EXPLAIN_LIMIT'
      ? `You can ask for up to 10 summaries every 10 minutes. Try again in ${wait}.`
      : `Too many requests at once. Try again in ${wait}.`;
  }
  return status === 404
    ? 'This report is no longer available.'
    : "Couldn't get the summary. Try again.";
}
