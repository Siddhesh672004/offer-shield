import { Component, computed, effect, inject, input, untracked } from '@angular/core';
import { Router } from '@angular/router';
import { Announcer } from '../../core/a11y/announcer';
import { Session } from '../../core/auth/session';
import { FullPageLoad, sameOriginPath } from '../../core/browser/full-page-load';
import { Notice } from '../../shared/ui/notice';

const LOGIN_FAILED = "Sign-in didn't complete. Try again.";
const SESSION_ENDED = 'Your session ended. Sign in again.';
const UNREACHABLE = "We can't reach OfferShield right now. Try again in a minute.";

/**
 * /login. "Sign in with Google" is a plain link: a full-page navigation to Spring Security's
 * `/oauth2/authorization/google`, never an HttpClient call. Query flags, set by authGuard and
 * the 401 interceptor: `login=failed` (Google sign-in failed or was cancelled), `session=ended`
 * (an API call answered 401), `unreachable=1&next=/path` (the server didn't answer; "Try again"
 * reloads `next`). Signed-in users are sent on to `next` or /app.
 */
@Component({
  selector: 'app-login',
  imports: [Notice],
  template: `
    <h1>Check a job offer before you reply</h1>
    <p class="lead">
      Upload an offer letter you received. OfferShield runs fixed checks on it and shows the
      evidence behind every flag, so you can decide what to do next.
    </p>

    @switch (session.status()) {
      @case ('unknown') {
        <p class="login__checking">Checking your sign-in…</p>
      }
      @case ('signed-in') {
        <p class="login__checking">You're signed in. Opening OfferShield…</p>
      }
      @case ('unreachable') {
        <app-notice tone="warn"
          ><p>{{ unreachableMessage }}</p></app-notice
        >
        <button type="button" class="button" (click)="retry()">Try again</button>
      }
      @default {
        @if (loginFailed()) {
          <app-notice tone="danger"
            ><p>{{ loginFailedMessage }}</p></app-notice
          >
        } @else if (sessionEnded()) {
          <app-notice tone="info"
            ><p>{{ sessionEndedMessage }}</p></app-notice
          >
        }
        <a class="button" href="/oauth2/authorization/google">Sign in with Google</a>
        <p class="fine-print login__note">
          We use your Google account only to sign you in. OfferShield never sees your Google password.
        </p>
      }
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
    .login__checking {
      min-height: var(--touch);
      color: var(--color-ink-soft);
    }
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
  /** Query parameters, bound by the router (withComponentInputBinding). */
  readonly login = input<string>();
  readonly sessionFlag = input<string>(undefined, { alias: 'session' });
  readonly next = input<string>();

  protected readonly session = inject(Session);
  protected readonly loginFailed = computed(() => this.login() === 'failed');
  protected readonly sessionEnded = computed(() => this.sessionFlag() === 'ended');
  protected readonly loginFailedMessage = LOGIN_FAILED;
  protected readonly sessionEndedMessage = SESSION_ENDED;
  protected readonly unreachableMessage = UNREACHABLE;

  private readonly announcer = inject(Announcer);
  private readonly router = inject(Router);
  private readonly fullPageLoad = inject(FullPageLoad);
  /** `next` only if it stays on this site. */
  private readonly safeNext = computed(() => sameOriginPath(this.next()));

  constructor() {
    // The shell (App) loads the session; this page only reads it.
    effect(() => {
      const status = this.session.status();
      let message: string | null = null;
      if (status === 'unreachable') {
        message = UNREACHABLE;
      } else if (status === 'signed-out') {
        message = this.loginFailed() ? LOGIN_FAILED : this.sessionEnded() ? SESSION_ENDED : null;
      }
      if (message) {
        const text = message;
        untracked(() => this.announcer.announce(text));
      }
    });

    effect(() => {
      if (this.session.status() === 'signed-in') {
        const next = this.safeNext();
        const target = next && !next.startsWith('/login') ? next : '/app';
        untracked(() => void this.router.navigateByUrl(target, { replaceUrl: true }));
      }
    });
  }

  /** Starts the app again from the server at the page the user asked for. */
  protected retry(): void {
    this.fullPageLoad.load(this.safeNext() ?? '/');
  }
}
