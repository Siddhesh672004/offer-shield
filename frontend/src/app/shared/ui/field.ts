import { Component, input } from '@angular/core';

/**
 * `aria-describedby` for a control inside `<app-field>`: the hint and error ids follow the
 * convention `<fieldId>-hint` and `<fieldId>-error`. Returns null when there is nothing to add.
 */
export function fieldDescribedBy(
  fieldId: string,
  shown: { readonly hint: boolean; readonly error: boolean },
): string | null {
  const ids = [shown.hint ? `${fieldId}-hint` : null, shown.error ? `${fieldId}-error` : null];
  const joined = ids.filter((id) => id !== null).join(' ');
  return joined === '' ? null : joined;
}

/**
 * Label, optional hint, the projected control, and the error message, laid out with the
 * `.field` classes. The control keeps its own id, `aria-invalid` and `aria-describedby`
 * (use `fieldDescribedBy`), so forms stay plain HTML and work with Signal Forms.
 *
 *   <app-field label="Department" fieldId="department" [error]="departmentError()">
 *     <input id="department" class="input" [formField]="form.department"
 *       [attr.aria-invalid]="departmentError() ? 'true' : null"
 *       [attr.aria-describedby]="departmentError() ? 'department-error' : null" />
 *   </app-field>
 */
@Component({
  selector: 'app-field',
  host: { class: 'field' },
  template: `
    <label class="field__label" [attr.for]="fieldId()">{{ label() }}</label>
    @if (hint(); as hint) {
      <p class="field__hint" [id]="fieldId() + '-hint'">{{ hint }}</p>
    }
    <ng-content />
    @if (error(); as error) {
      <p class="field__error" [id]="fieldId() + '-error'">{{ error }}</p>
    }
  `,
})
export class Field {
  readonly label = input.required<string>();
  /** The id of the projected control. */
  readonly fieldId = input.required<string>();
  readonly hint = input<string | null>(null);
  readonly error = input<string | null>(null);
}
