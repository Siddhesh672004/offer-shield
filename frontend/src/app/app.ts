import { Component, DOCUMENT, Injector, afterNextRender, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, skip } from 'rxjs';
import { Announcer } from './core/a11y/announcer';
import { Session } from './core/auth/session';
import { Notice } from './shared/ui/notice';

/** The shell: letterhead, one sheet of paper for the page, and the single live status region. */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Notice],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly session = inject(Session);
  protected readonly announcer = inject(Announcer);
  protected readonly signingOut = signal(false);
  protected readonly signOutFailed = signal(false);

  private readonly router = inject(Router);
  private readonly document = inject(DOCUMENT);
  private readonly injector = inject(Injector);

  constructor() {
    void this.session.load();

    const navigationEnds = this.router.events.pipe(
      filter((event) => event instanceof NavigationEnd),
      takeUntilDestroyed(),
    );
    // A "Couldn't sign out" notice belongs to the page it appeared on.
    navigationEnds.subscribe(() => this.signOutFailed.set(false));
    // After each route change (not the first page load) move focus to the new page's <h1>.
    navigationEnds
      .pipe(skip(1))
      .subscribe(() => afterNextRender(() => this.focusHeading(), { injector: this.injector }));
  }

  protected skipToMain(event: Event): void {
    event.preventDefault();
    this.document.getElementById('main')?.focus();
  }

  protected async signOut(): Promise<void> {
    this.signingOut.set(true);
    this.signOutFailed.set(false);
    try {
      await this.session.logout();
      this.announcer.announce('You signed out.');
      await this.router.navigateByUrl('/login');
    } catch {
      this.signOutFailed.set(true);
      this.announcer.announce("Couldn't sign out. Try again.");
    } finally {
      this.signingOut.set(false);
    }
  }

  private focusHeading(): void {
    const heading = this.document.querySelector<HTMLElement>('main h1');
    if (!heading) {
      return;
    }
    if (!heading.hasAttribute('tabindex')) {
      heading.setAttribute('tabindex', '-1');
    }
    heading.focus();
  }
}
