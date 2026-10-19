import { Component, computed, effect, inject, input, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Announcer } from '../../core/a11y/announcer';
import { Session } from '../../core/auth/session';
import { Notice } from '../../shared/ui/notice';

const LOGIN_FAILED = "Sign-in didn't complete. Try again.";

/**
 * /login. "Sign in with Google" is a plain link: a full-page navigation to Spring Security's
 * `/oauth2/authorization/google`, never an HttpClient call. A failed or cancelled sign-in comes
 * back as `/app?login=failed`, which authGuard forwards here as `?login=failed`.
 */
@Component({
  selector: 'app-login',
  imports: [Notice, RouterLink],
  template: `
    <h1>Check a job offer before you reply</h1>
    <p class="lead">
      Upload an offer letter you received. OfferShield runs fixed checks on it and shows the
      evidence behind every flag, so you can decide what to do next.
    </p>

    @if (loginFailed()) {
      <app-notice tone="danger"
        ><p>{{ loginFailedMessage }}</p></app-notice
      >
    }
    @if (session.status() === 'unreachable') {
      <app-notice tone="warn">
        <p>We couldn't reach OfferShield. Check your connection, then reload this page.</p>
      </app-notice>
    }

    @if (session.me(); as me) {
      <p>
        You're signed in as <span translate="no">{{ me.name }}</span
        >.
      </p>
      <a class="button" routerLink="/app">Go to your offers</a>
    } @else {
      <a class="button" href="/oauth2/authorization/google">Sign in with Google</a>
      <p class="fine-print login__note">
        We use your Google account only to sign you in. OfferShield never sees your Google password.
      </p>
    }

    <ul class="login__points">
      <li>Every flag shows its evidence and where that evidence came from.</li>
      <li>If your college uses OfferShield, your TPO can review an offer with you.</li>
      <li>
        Scam reports from other users count as reports, not facts, until an admin confirms them.
      </li>
    </ul>
  `,
  styles: `
    .login__note {
      margin-top: var(--space-3);
    }
    .login__points {
      max-width: var(--measure);
      margin: var(--space-6) 0 0;
      padding: var(--space-5) 0 0 var(--space-5);
      border-top: 1px solid var(--color-rule);
      color: var(--color-ink-soft);
    }
    .login__points li + li {
      margin-top: var(--space-2);
    }
  `,
})
export class Login {
  /** `?login=failed`, bound by the router (withComponentInputBinding). */
  readonly login = input<string>();

  protected readonly session = inject(Session);
  protected readonly loginFailed = computed(() => this.login() === 'failed');
  protected readonly loginFailedMessage = LOGIN_FAILED;

  private readonly announcer = inject(Announcer);

  constructor() {
    // The shell (App) loads the session; this page only reads it.
    effect(() => {
      if (this.loginFailed()) {
        untracked(() => this.announcer.announce(LOGIN_FAILED));
      }
    });
  }
}
