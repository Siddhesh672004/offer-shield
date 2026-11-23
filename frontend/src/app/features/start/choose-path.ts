import { Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { PathChoice } from '../../core/auth/path-choice';
import { Session } from '../../core/auth/session';
import { PageHeader } from '../../shared/ui/page-header';

/** /start: shown after a student's first sign-in (choosePathGuard). */
@Component({
  selector: 'app-choose-path',
  imports: [PageHeader, RouterLink],
  template: `
    <app-page-header heading="How do you want to use OfferShield?">
      <p>You can join your college later if you continue on your own.</p>
    </app-page-header>

    <div class="choices">
      <a class="choice" routerLink="/join">
        <span class="choice__icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" focusable="false">
            <path d="M3 9.5 12 5l9 4.5-9 4.5-9-4.5Z" />
            <path d="M6.5 11.25v4.25c0 1.25 2.5 2.75 5.5 2.75s5.5-1.5 5.5-2.75v-4.25M21 9.5v5" />
          </svg>
        </span>
        <span class="choice__title">Join my college</span>
        <span class="choice__text">
          Send a request to your college's TPO (training and placement officer). Once they approve
          it, you can ask them to review an offer with you.
        </span>
        <svg class="choice__arrow" viewBox="0 0 20 20" aria-hidden="true" focusable="false">
          <path d="m8 5 5 5-5 5" />
        </svg>
      </a>
      <button type="button" class="choice" (click)="continueOnMyOwn()">
        <span class="choice__icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" focusable="false">
            <circle cx="12" cy="8.5" r="3.75" />
            <path d="M4.75 19.5c.9-3.4 3.75-5.25 7.25-5.25s6.35 1.85 7.25 5.25" />
          </svg>
        </span>
        <span class="choice__title">Continue on my own</span>
        <span class="choice__text">Check offers and report scams without linking a college.</span>
        <svg class="choice__arrow" viewBox="0 0 20 20" aria-hidden="true" focusable="false">
          <path d="m8 5 5 5-5 5" />
        </svg>
      </button>
    </div>
  `,
  styles: `
    .choices {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 20rem), 1fr));
      gap: var(--space-4);
      max-width: 52rem;
    }
    .choice {
      position: relative;
      display: grid;
      grid-template-columns: auto minmax(0, 1fr);
      align-content: start;
      gap: var(--space-1) var(--space-4);
      width: 100%;
      min-height: var(--touch);
      padding: var(--space-5) 2.75rem var(--space-5) var(--space-5);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-lg);
      background: var(--color-surface);
      box-shadow: var(--shadow-xs);
      color: var(--color-text);
      font: inherit;
      text-align: left;
      text-decoration: none;
      cursor: pointer;
      touch-action: manipulation;
    }
    @media (hover: hover) {
      .choice:hover {
        border-color: var(--color-primary-border);
        box-shadow: var(--shadow-md);
        color: var(--color-text);
      }
      .choice:hover .choice__arrow {
        color: var(--color-primary);
      }
    }
    .choice__icon {
      display: grid;
      grid-row: span 2;
      place-items: center;
      width: 2.75rem;
      height: 2.75rem;
      border-radius: var(--radius-md);
      background: var(--color-primary-subtle);
      color: var(--color-primary);
    }
    svg {
      fill: none;
      stroke: currentColor;
      stroke-width: 1.6;
      stroke-linecap: round;
      stroke-linejoin: round;
    }
    .choice__icon svg {
      width: 1.5rem;
      height: 1.5rem;
    }
    .choice__title {
      align-self: center;
      font-size: var(--text-md);
      font-weight: var(--weight-semibold);
      letter-spacing: -0.01em;
    }
    .choice__text {
      color: var(--color-text-muted);
    }
    .choice__arrow {
      position: absolute;
      top: var(--space-5);
      right: var(--space-4);
      width: 1.25rem;
      height: 1.25rem;
      color: var(--color-text-muted);
    }
    @media (prefers-reduced-motion: no-preference) {
      .choice {
        transition:
          border-color var(--duration-fast) ease,
          box-shadow var(--duration-base) ease,
          transform var(--duration-fast) var(--ease-out);
      }
      .choice__arrow {
        transition: transform var(--duration-base) var(--ease-out);
      }
      @media (hover: hover) {
        .choice:hover .choice__arrow {
          transform: translateX(2px);
        }
      }
      .choice:active {
        transform: scale(0.99);
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
