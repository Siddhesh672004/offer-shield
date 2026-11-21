import { Component } from '@angular/core';

/**
 * A small tag that marks something the AI produced: "AI-extracted" by default, or the projected
 * text ("AI-written summary"). CLAUDE.md: AI-derived fields are always labelled.
 */
@Component({
  selector: 'app-ai-tag',
  host: { class: 'ai-tag' },
  template: `<ng-content>AI-extracted</ng-content>`,
  styles: `
    :host {
      display: inline-flex;
      align-items: center;
      padding: 0 var(--space-2);
      border: 1px solid var(--color-primary-border);
      border-radius: var(--radius-xs);
      background: var(--color-primary-subtle);
      color: var(--color-primary-strong);
      font-size: 0.8125rem;
      font-weight: var(--weight-medium);
      line-height: 1.6;
      white-space: nowrap;
    }
  `,
})
export class AiTag {}
