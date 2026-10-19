import { DOCUMENT, Service, inject } from '@angular/core';

/** Backslashes, whitespace and control characters: browsers may turn `/\x` or `/<tab>/x` into `//x`. */
const UNSAFE_CHARACTERS = /[\\\s\u0000-\u001f\u007f]/;

/**
 * `value` if it is a path on this site (`/join?x=1`), otherwise `null`. Rejects absolute and
 * protocol-relative URLs (`https://…`, `//…`) and the backslash/whitespace tricks that browsers
 * turn into them, so a `?next=` value can never send someone to another site.
 */
export function sameOriginPath(value: unknown): string | null {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//')) {
    return null;
  }
  return UNSAFE_CHARACTERS.test(value) ? null : value;
}

/**
 * A full browser load (the app starts again from the server), unlike a router navigation.
 * Callers pass a path already checked with `sameOriginPath`. A separate service so tests can
 * check the call without leaving the page.
 */
@Service()
export class FullPageLoad {
  private readonly document = inject(DOCUMENT);

  load(path: string): void {
    this.document.location.assign(path);
  }
}
