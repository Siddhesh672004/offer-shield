import { FieldState, SchemaPath, required, validate } from '@angular/forms/signals';

/** `required` plus "not only spaces", with one message for both. */
export function filledIn(path: SchemaPath<string>, message: string): void {
  required(path, { message });
  validate(path, ({ value }) =>
    value().trim() === '' ? { kind: 'required', message } : undefined,
  );
}

/** The first error of a field the user has touched (or tried to submit), else null. */
export function firstError(state: FieldState<string>): string | null {
  const errors = state.errors();
  return state.touched() && errors.length > 0 ? (errors[0].message ?? 'Check this field.') : null;
}
