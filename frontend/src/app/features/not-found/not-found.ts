import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Button } from '../../shared/ui/button';

/** Shown for unknown paths and for areas the account can't open (a wrong role doesn't match). */
@Component({
  selector: 'app-not-found',
  imports: [Button, RouterLink],
  template: `
    <div class="not-found">
      <span class="not-found__icon" aria-hidden="true">
        <svg viewBox="0 0 24 24" focusable="false">
          <circle cx="10.75" cy="10.75" r="6" />
          <path d="m15.25 15.25 4.5 4.5" />
        </svg>
      </span>
      <h1>Page not found</h1>
      <p>This address doesn't exist, or it isn't available for your account.</p>
      <a appButton variant="secondary" routerLink="/">Go to the start page</a>
    </div>
  `,
  styles: `
    .not-found {
      display: flex;
      flex-direction: column;
      align-items: center;
      padding: var(--space-8) 0;
      text-align: center;
    }
    .not-found__icon {
      display: grid;
      place-items: center;
      width: 3.5rem;
      height: 3.5rem;
      margin-bottom: var(--space-5);
      border-radius: var(--radius-full);
      background: var(--color-surface-sunken);
      color: var(--color-text-muted);
    }
    svg {
      width: 1.75rem;
      height: 1.75rem;
      fill: none;
      stroke: currentColor;
      stroke-width: 1.6;
      stroke-linecap: round;
    }
    p {
      margin-bottom: var(--space-6);
      color: var(--color-text-secondary);
    }
  `,
})
export class NotFound {}
