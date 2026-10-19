import { Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { PathChoice } from '../../core/auth/path-choice';
import { Session } from '../../core/auth/session';

/** /start: shown after a student's first sign-in (choosePathGuard). */
@Component({
  selector: 'app-choose-path',
  imports: [RouterLink],
  template: `
    <h1>How do you want to use OfferShield?</h1>
    <p class="lead">You can join your college later if you continue on your own.</p>

    <div class="choices">
      <a class="choice" routerLink="/join">
        <span class="choice__title">Join my college</span>
        <span class="choice__text">
          Send a request to your college's TPO (training and placement officer). Once they approve
          it, you can ask them to review an offer with you.
        </span>
      </a>
      <button type="button" class="choice" (click)="continueOnMyOwn()">
        <span class="choice__title">Continue on my own</span>
        <span class="choice__text">Check offers and report scams without linking a college.</span>
      </button>
    </div>
  `,
  styles: `
    .choices {
      display: grid;
      gap: var(--space-3);
      max-width: 40rem;
    }
    .choice {
      display: grid;
      gap: var(--space-1);
      width: 100%;
      min-height: var(--touch);
      padding: var(--space-4) var(--space-5);
      border: 1px solid var(--color-rule);
      border-left: 4px solid var(--color-accent);
      border-radius: var(--radius-sm);
      background: var(--color-paper);
      color: var(--color-ink);
      font: inherit;
      text-align: left;
      text-decoration: none;
      cursor: pointer;
      touch-action: manipulation;
    }
    .choice:hover {
      background: var(--color-accent-wash);
      color: var(--color-ink);
    }
    .choice__title {
      color: var(--color-accent-strong);
      font-family: var(--font-letter);
      font-size: var(--text-lg);
      font-weight: 700;
    }
    .choice__text {
      color: var(--color-ink-soft);
    }
    @media (prefers-reduced-motion: no-preference) {
      .choice {
        transition: background-color 120ms ease;
      }
    }
  `,
})
export class ChoosePath {
  private readonly session = inject(Session);
  private readonly pathChoice = inject(PathChoice);
  private readonly router = inject(Router);

  protected async continueOnMyOwn(): Promise<void> {
    const me = this.session.me();
    if (me) {
      this.pathChoice.chooseOwnPath(me.userId);
    }
    await this.router.navigateByUrl('/app');
  }
}
