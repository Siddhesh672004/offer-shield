import { Component, booleanAttribute, computed, input } from '@angular/core';

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger';
export type ButtonSize = 'sm' | 'md' | 'lg';

/**
 * Styles a native `<button>` or `<a>` as a button (the `.button` classes in styles.css), so it
 * keeps its own semantics, `type`, `href`, `disabled` and `routerLink`.
 *
 * `loading` shows a decorative spinner and sets `aria-busy`; the label stays readable. Set
 * `[disabled]` yourself while the work runs, and change the label ("Sending…") if it helps.
 *
 *   <button appButton type="submit" [loading]="saving()" [disabled]="saving()">Save</button>
 *   <a appButton variant="secondary" routerLink="/join">Join my college</a>
 */
@Component({
  selector: 'button[appButton], a[appButton]',
  host: {
    '[class]': 'classes()',
    '[attr.aria-busy]': 'loading() ? "true" : null',
  },
  template: `
    @if (loading()) {
      <span class="button__spinner" aria-hidden="true"></span>
    }
    <ng-content />
  `,
})
export class Button {
  readonly variant = input<ButtonVariant>('primary');
  readonly size = input<ButtonSize>('md');
  readonly block = input(false, { transform: booleanAttribute });
  readonly loading = input(false, { transform: booleanAttribute });

  protected readonly classes = computed(() => {
    const classes = ['button', `button--${this.variant()}`];
    if (this.size() !== 'md') {
      classes.push(`button--${this.size()}`);
    }
    if (this.block()) {
      classes.push('button--block');
    }
    return classes.join(' ');
  });
}
