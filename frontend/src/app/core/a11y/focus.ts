import { Injector, afterNextRender } from '@angular/core';

const FOCUSABLE = 'a[href], button, input, select, textarea, [tabindex]';

/**
 * After the next render, moves focus to the first element under `root` that matches one of the
 * selectors, in order (e.g. the next row's heading, else the list heading). A heading or notice
 * gets `tabindex="-1"` so it can take focus without joining the tab order.
 */
export function focusAfterRender(
  root: HTMLElement,
  injector: Injector,
  ...selectors: readonly string[]
): void {
  afterNextRender(
    () => {
      for (const selector of selectors) {
        const element = root.querySelector<HTMLElement>(selector);
        if (element) {
          if (!element.matches(FOCUSABLE)) {
            element.setAttribute('tabindex', '-1');
          }
          element.focus();
          return;
        }
      }
    },
    { injector },
  );
}
