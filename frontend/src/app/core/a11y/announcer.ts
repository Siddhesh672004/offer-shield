import { Service, signal } from '@angular/core';

/**
 * Feeds the app's single `role="status"` live region (rendered once by the shell), so screen
 * readers hear state changes such as "Join request sent".
 */
@Service()
export class Announcer {
  private readonly text = signal('');

  readonly message = this.text.asReadonly();

  announce(message: string): void {
    // A live region only speaks when its text changes, so a repeat gets a trailing no-break space.
    this.text.update((current) => (current === message ? `${message} ` : message));
  }
}
