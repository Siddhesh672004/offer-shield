import { Component, computed, effect, inject, input, untracked } from '@angular/core';
import { Router } from '@angular/router';
import { Announcer } from '../../core/a11y/announcer';
import { Session } from '../../core/auth/session';
import { sameOriginPath } from '../../core/browser/full-page-load';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Spinner } from '../../shared/ui/loading';
import { Notice } from '../../shared/ui/notice';
import { RiskBandIndicator } from '../../shared/ui/risk-band';
import { SourceKindTag } from '../../shared/ui/source-kind';

const LOGIN_FAILED = "Sign-in didn't complete. Try again.";
const SESSION_ENDED = 'Your session ended. Sign in again.';
const UNREACHABLE = "We can't reach OfferShield right now. Try again in a minute.";

/**
 * /login. "Sign in with Google" is a plain link: a full-page navigation to Spring Security's
 * `/oauth2/authorization/google`, never an HttpClient call. Query flags, set by authGuard and
 * the 401 interceptor: `login=failed` (Google sign-in failed or was cancelled), `session=ended`
 * (an API call answered 401), `unreachable=1&next=/path` (the server didn't answer; "Try again"
 * is a plain link that loads `next` from the server). Signed-in users go on to `next` or /app.
 *
 * The example report beside the sign-in is static and made from a fake letter.
 */
@Component({
  selector: 'app-login',
  imports: [Badge, Button, Notice, RiskBandIndicator, SourceKindTag, Spinner],
  template: `
    <div class="login">
      <section class="login__intro" aria-labelledby="login-heading">
        <h1 id="login-heading">Check a job offer before you reply</h1>
        <p class="lead">
          Upload an offer letter you received. OfferShield runs fixed checks on it and shows the
          evidence behind every flag, so you can decide what to do next.
        </p>

        <div class="login__action">
          @switch (session.status()) {
            @case ('unknown') {
              <app-spinner label="Checking your sign-in…" />
            }
            @case ('signed-in') {
              <app-spinner label="You're signed in. Opening OfferShield…" />
            }
            @case ('unreachable') {
              <app-notice tone="warn"
                ><p>{{ unreachableMessage }}</p></app-notice
              >
              <!-- A plain link, not routerLink: the browser loads the page again from the server. -->
              <a appButton size="lg" [href]="retryPath()">Try again</a>
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
              <a
                appButton
                variant="secondary"
                size="lg"
                class="login__google"
                href="/oauth2/authorization/google"
              >
                <svg viewBox="0 0 18 18" aria-hidden="true" focusable="false">
                  <path
                    fill="#4285F4"
                    d="M17.64 9.2c0-.64-.06-1.25-.16-1.84H9v3.48h4.84a4.14 4.14 0 0 1-1.8 2.72v2.26h2.92c1.7-1.57 2.68-3.88 2.68-6.62Z"
                  />
                  <path
                    fill="#34A853"
                    d="M9 18c2.43 0 4.47-.8 5.96-2.18l-2.92-2.26c-.8.54-1.84.86-3.04.86-2.34 0-4.32-1.58-5.03-3.7H.96v2.33A9 9 0 0 0 9 18Z"
                  />
                  <path
                    fill="#FBBC05"
                    d="M3.97 10.72A5.4 5.4 0 0 1 3.68 9c0-.6.1-1.18.29-1.72V4.95H.96A9 9 0 0 0 0 9c0 1.45.35 2.83.96 4.05l3.01-2.33Z"
                  />
                  <path
                    fill="#EA4335"
                    d="M9 3.58c1.32 0 2.5.45 3.44 1.35l2.58-2.58A9 9 0 0 0 .96 4.95l3.01 2.33C4.68 5.16 6.66 3.58 9 3.58Z"
                  />
                </svg>
                Sign in with Google
              </a>
              <p class="fine-print login__note">
                We use your Google account only to sign you in. OfferShield never sees your Google
                password.
              </p>
            }
          }
        </div>

        <ul class="login__points">
          <li>
            <svg viewBox="0 0 20 20" aria-hidden="true" focusable="false">
              <path
                d="M11.5 3H6a1.5 1.5 0 0 0-1.5 1.5v11A1.5 1.5 0 0 0 6 17h8a1.5 1.5 0 0 0 1.5-1.5V7L11.5 3Z"
              />
              <path d="M11.5 3v4h4M7.5 11h5M7.5 14h3" />
            </svg>
            <span>Every flag shows its evidence and where that evidence came from.</span>
          </li>
          <li>
            <svg viewBox="0 0 20 20" aria-hidden="true" focusable="false">
              <path d="M2.5 8 10 4l7.5 4L10 12 2.5 8Z" />
              <path d="M5.5 9.75v3.5c0 1 2 2.25 4.5 2.25s4.5-1.25 4.5-2.25v-3.5" />
            </svg>
            <span>If your college uses OfferShield, your TPO can review an offer with you.</span>
          </li>
          <li>
            <svg viewBox="0 0 20 20" aria-hidden="true" focusable="false">
              <path d="M4.5 17.5V3.5M4.5 3.5h9.5l-2 3.5 2 3.5H4.5" />
            </svg>
            <span
              >Scam reports from other users count as reports, not facts, until an admin confirms
              them.</span
            >
          </li>
        </ul>
      </section>

      <figure class="example">
        <div class="example__file">
          <svg viewBox="0 0 20 20" aria-hidden="true" focusable="false">
            <path
              d="M11.5 3H6a1.5 1.5 0 0 0-1.5 1.5v11A1.5 1.5 0 0 0 6 17h8a1.5 1.5 0 0 0 1.5-1.5V7L11.5 3Z"
            />
            <path d="M11.5 3v4h4" />
          </svg>
          <span translate="no">offer-letter.pdf</span>
          <app-badge class="example__tag">Example</app-badge>
        </div>
        <app-risk-band band="HIGH" size="lg" [score]="86" />
        <ul class="example__signals">
          <li>
            <p class="example__signal">Asks you to pay before joining</p>
            <p class="example__evidence">“…pay a refundable training fee of ₹4,999…”</p>
            <app-source-kind [kind]="1" />
          </li>
          <li>
            <p class="example__signal">Sender's domain was registered 9 days before the letter</p>
            <p class="example__evidence" translate="no">careers-example-hiring.com</p>
            <app-source-kind [kind]="2" />
          </li>
          <li>
            <p class="example__signal">Pushes you to decide fast</p>
            <p class="example__evidence">
              “…confirm within 24 hours or the seat goes to someone else.”
            </p>
            <app-source-kind [kind]="1" />
          </li>
        </ul>
        <figcaption class="example__caption">
          An example report, made from a fake letter.
        </figcaption>
      </figure>
    </div>
  `,
  styles: `
    .login {
      display: grid;
      grid-template-columns: minmax(0, 1fr);
      gap: var(--space-7);
      align-items: start;
    }
    @media (min-width: 60rem) {
      .login {
        grid-template-columns: minmax(0, 1.05fr) minmax(0, 1fr);
        gap: var(--space-8);
        padding-top: var(--space-4);
      }
    }
    h1 {
      max-width: 15ch;
      margin-bottom: var(--space-4);
      font-size: var(--text-3xl);
      letter-spacing: -0.025em;
      line-height: 1.1;
    }
    .login__action {
      margin: var(--space-6) 0 var(--space-7);
    }
    .login__google {
      min-width: min(100%, 17rem);
    }
    .login__note {
      margin: var(--space-3) 0 0;
    }
    .login__points {
      display: grid;
      gap: var(--space-4);
      margin: 0;
      padding: var(--space-6) 0 0;
      border-top: 1px solid var(--color-border);
      color: var(--color-text-secondary);
      list-style: none;
    }
    .login__points li {
      display: flex;
      gap: var(--space-3);
      align-items: flex-start;
    }
    svg {
      width: 1.25rem;
      height: 1.25rem;
      fill: none;
      stroke: currentColor;
      stroke-width: 1.5;
      stroke-linecap: round;
      stroke-linejoin: round;
    }
    .login__points svg {
      margin-top: 0.125rem;
      color: var(--color-primary);
    }
    .login__google svg {
      stroke: none;
    }

    /* The example report: the one raised surface on the page */
    .example {
      margin: 0;
      padding: var(--space-5);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-lg);
      background: var(--color-surface);
      box-shadow: var(--shadow-lg);
    }
    .example__file {
      display: flex;
      align-items: center;
      gap: var(--space-2);
      margin-bottom: var(--space-4);
      color: var(--color-text-muted);
      font-size: var(--text-sm);
      font-weight: var(--weight-medium);
    }
    .example__tag {
      margin-left: auto;
    }
    .example__signals {
      margin: var(--space-2) 0 0;
      padding: 0;
      list-style: none;
    }
    .example__signals li {
      padding: var(--space-4) 0;
      border-bottom: 1px solid var(--color-border);
    }
    .example__signals p {
      margin: 0;
    }
    .example__signal {
      font-weight: var(--weight-semibold);
    }
    .example__signals .example__evidence {
      margin: var(--space-2) 0;
      padding: var(--space-2) var(--space-3);
      border-left: 3px solid var(--color-border-strong);
      border-radius: 0 var(--radius-xs) var(--radius-xs) 0;
      background: var(--color-surface-muted);
      color: var(--color-text-secondary);
      font-size: var(--text-sm);
      overflow-wrap: anywhere;
    }
    .example__caption {
      margin-top: var(--space-4);
      color: var(--color-text-muted);
      font-size: var(--text-sm);
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
  /** `next` only if it stays on this site (no open redirect). */
  private readonly safeNext = computed(() => sameOriginPath(this.next()));
  /** "Try again" starts the app again from the server at the page the user asked for. */
  protected readonly retryPath = computed(() => this.safeNext() ?? '/');

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
}
