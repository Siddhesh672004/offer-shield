import { Component, ElementRef, afterNextRender, input, output, viewChild } from '@angular/core';
import { Button } from './button';

let nextId = 0;

/**
 * An inline "are you sure?" step for a destructive action (reject, revert, delete). It replaces
 * the action's button: the question, a red confirm button (focused when it appears, described
 * by the question) and Cancel. Escape cancels. The parent puts focus back on its trigger after
 * `cancelled`.
 *
 *   <app-confirm-step question="Delete Northwind and its domains?" confirmLabel="Delete company"
 *     (confirmed)="delete(company)" (cancelled)="keep(company)" />
 */
@Component({
  selector: 'app-confirm-step',
  imports: [Button],
  host: {
    class: 'confirm-step',
    role: 'group',
    '[attr.aria-labelledby]': 'questionId',
    '(keydown.escape)': 'cancelled.emit()',
  },
  template: `
    <p class="confirm-step__question" [id]="questionId">{{ question() }}</p>
    <div class="cluster">
      <button
        #confirmButton
        appButton
        type="button"
        variant="danger"
        size="sm"
        [attr.aria-describedby]="questionId"
        (click)="confirmed.emit()"
      >
        {{ confirmLabel() }}
      </button>
      <button appButton type="button" variant="ghost" size="sm" (click)="cancelled.emit()">
        Cancel
      </button>
    </div>
  `,
  styles: `
    :host {
      display: grid;
      gap: var(--space-2);
      padding: var(--space-3) var(--space-4);
      border: 1px solid var(--color-danger-border);
      border-radius: var(--radius-md);
      background: var(--color-danger-subtle);
    }
    .confirm-step__question {
      margin: 0;
      font-weight: var(--weight-medium);
    }
  `,
})
export class ConfirmStep {
  readonly question = input.required<string>();
  readonly confirmLabel = input.required<string>();
  readonly confirmed = output<void>();
  readonly cancelled = output<void>();

  protected readonly questionId = `confirm-step-${++nextId}`;
  private readonly confirmButton = viewChild.required('confirmButton', {
    read: ElementRef<HTMLButtonElement>,
  });

  constructor() {
    afterNextRender(() => this.confirmButton().nativeElement.focus());
  }
}
