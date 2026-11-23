import { Component, computed, input } from '@angular/core';

/** PRD §7: every signal comes from one of five kinds of source, and the report shows which. */
export type SourceKind = 1 | 2 | 3 | 4 | 5;

export const SOURCE_KIND_NAMES: Readonly<Record<SourceKind, string>> = {
  1: 'the letter itself',
  2: 'public registries',
  3: 'curated lists',
  4: 'confirmed reports',
  5: 'user reports',
};

/**
 * "Source N: name" under a signal's evidence, plus an "AI-extracted" tag when the AI found the
 * value (CLAUDE.md: AI-derived fields are labelled).
 */
@Component({
  selector: 'app-source-kind',
  host: { class: 'source-kind' },
  template: `
    <span class="source-kind__tag">Source {{ kind() }}: {{ name() }}</span>
    @if (aiExtracted()) {
      <span class="source-kind__tag source-kind__tag--ai">AI-extracted</span>
    }
  `,
  styles: `
    :host {
      display: inline-flex;
      flex-wrap: wrap;
      gap: var(--space-1) var(--space-2);
    }
    .source-kind__tag {
      display: inline-flex;
      align-items: center;
      padding: 0 var(--space-2);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-xs);
      background: var(--color-surface-muted);
      color: var(--color-text-muted);
      font-size: 0.8125rem;
      font-weight: var(--weight-medium);
      line-height: 1.6;
    }
    .source-kind__tag--ai {
      border-color: var(--color-primary-border);
      background: var(--color-primary-subtle);
      color: var(--color-primary-strong);
    }
  `,
})
export class SourceKindTag {
  readonly kind = input.required<SourceKind>();
  readonly aiExtracted = input(false);

  protected readonly name = computed(() => SOURCE_KIND_NAMES[this.kind()]);
}
