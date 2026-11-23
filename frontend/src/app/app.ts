import {
  Component,
  DOCUMENT,
  Injector,
  afterNextRender,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, skip } from 'rxjs';
import { Announcer } from './core/a11y/announcer';
import { Role, Session } from './core/auth/session';
import { AccountMenu } from './core/layout/account-menu';
import { Notice } from './shared/ui/notice';

interface NavLink {
  readonly path: string;
  readonly label: string;
}

/** The extra app-bar links of each role (everyone also has "My offers"). */
const STAFF_LINKS: Readonly<Record<Role, readonly NavLink[]>> = {
  USER: [],
  TPO: [
    { path: '/tpo/members', label: 'Members' },
    { path: '/tpo/reviews', label: 'Reviews' },
  ],
  ADMIN: [
    { path: '/admin/colleges', label: 'Colleges' },
    { path: '/admin/scam-reports', label: 'Scam reports' },
    { path: '/admin/companies', label: 'Companies' },
  ],
};

/**
 * The shell: the app bar (brand, main navigation, account menu), the page area, the footer
 * disclaimer, and the single live status region.
 */
@Component({
  selector: 'app-root',
  imports: [AccountMenu, Notice, RouterLink, RouterLinkActive, RouterOutlet],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly session = inject(Session);
  protected readonly announcer = inject(Announcer);
  protected readonly signingOut = signal(false);
  protected readonly signOutFailed = signal(false);
  protected readonly staffLinks = STAFF_LINKS;

  private readonly router = inject(Router);
  private readonly document = inject(DOCUMENT);
  private readonly injector = inject(Injector);
  private readonly accountMenu = viewChild(AccountMenu);

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
      // Close the menu so it doesn't cover the notice.
      this.accountMenu()?.close();
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
