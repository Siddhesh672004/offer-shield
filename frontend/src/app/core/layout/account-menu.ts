import { Component, ElementRef, computed, input, output, viewChild } from '@angular/core';
import { Me } from '../auth/session';

/**
 * The signed-in person's menu in the app bar: initials, name, email, role, and "Sign out".
 * It is a native popover (`popovertarget`), so opening, closing, Escape and outside clicks
 * need no script. The shell (App) does the signing out and closes the menu if it fails.
 */
@Component({
  selector: 'app-account-menu',
  template: `
    <button type="button" class="trigger" popovertarget="account-menu">
      <span class="avatar" aria-hidden="true">{{ initials() }}</span>
      <span class="name" translate="no"
        ><span class="visually-hidden">Account: </span>{{ me().name }}</span
      >
      <svg class="chevron" viewBox="0 0 16 16" aria-hidden="true" focusable="false">
        <path d="m4.5 6.25 3.5 3.5 3.5-3.5" />
      </svg>
    </button>

    <div #menu id="account-menu" class="menu" popover>
      <div class="who">
        <span class="avatar avatar--lg" aria-hidden="true">{{ initials() }}</span>
        <div class="details">
          <p class="details__name" translate="no">{{ me().name }}</p>
          <p class="details__meta" translate="no">{{ me().email }}</p>
          <p class="details__meta">{{ roleLabel() }}</p>
        </div>
      </div>
      <button type="button" class="signout" [disabled]="busy()" (click)="signOut.emit()">
        <svg viewBox="0 0 20 20" aria-hidden="true" focusable="false">
          <path d="M8 4.5H5.5a1 1 0 0 0-1 1v9a1 1 0 0 0 1 1H8M12.5 13.5 16 10l-3.5-3.5M16 10H8" />
        </svg>
        {{ busy() ? 'Signing out…' : 'Sign out' }}
      </button>
    </div>
  `,
  styles: `
    :host {
      display: block;
    }
    svg {
      fill: none;
      stroke: var(--color-text-muted);
      stroke-linecap: round;
      stroke-linejoin: round;
    }
    .trigger {
      display: inline-flex;
      align-items: center;
      gap: var(--space-2);
      min-height: var(--touch);
      padding: 0 var(--space-2) 0 0.375rem;
      border: 0;
      border-radius: var(--radius-full);
      background: transparent;
      color: var(--color-text);
      font: inherit;
      font-size: 0.9375rem;
      font-weight: var(--weight-medium);
      cursor: pointer;
      anchor-name: --account;
    }
    .trigger:hover {
      background: var(--color-surface-muted);
    }
    .avatar {
      display: grid;
      place-items: center;
      width: 2rem;
      height: 2rem;
      border-radius: var(--radius-full);
      background: var(--color-primary-subtle);
      color: var(--color-primary-strong);
      font-size: 0.8125rem;
      font-weight: var(--weight-bold);
    }
    .avatar--lg {
      width: 2.5rem;
      height: 2.5rem;
      font-size: 0.9375rem;
    }
    .name {
      max-width: 12rem;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .chevron {
      width: 1rem;
      height: 1rem;
      stroke-width: 1.5;
    }

    /* Under the trigger: fixed fallback first, anchored where the browser supports it. */
    .menu {
      position: fixed;
      inset: auto;
      top: calc(var(--appbar-height) - var(--space-1));
      right: max(var(--gutter), calc((100vw - var(--container-max)) / 2 + var(--gutter)));
      width: min(20rem, calc(100vw - 2 * var(--gutter)));
      margin: 0;
      padding: var(--space-2);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-lg);
      background: var(--color-surface);
      box-shadow: var(--shadow-lg);
      color: var(--color-text);
    }
    .who {
      display: flex;
      align-items: center;
      gap: var(--space-3);
      margin-bottom: var(--space-1);
      padding: var(--space-2) var(--space-2) var(--space-3);
      border-bottom: 1px solid var(--color-border);
    }
    .details {
      min-width: 0;
    }
    .details p {
      margin: 0;
      overflow-wrap: anywhere;
    }
    .details__name {
      font-weight: var(--weight-semibold);
    }
    .details__meta {
      color: var(--color-text-muted);
      font-size: var(--text-sm);
      line-height: var(--leading-snug);
    }
    .signout {
      display: flex;
      align-items: center;
      gap: var(--space-2);
      width: 100%;
      min-height: var(--touch);
      padding: 0 var(--space-2);
      border: 0;
      border-radius: var(--radius-md);
      background: transparent;
      color: var(--color-text);
      font: inherit;
      font-weight: var(--weight-medium);
      cursor: pointer;
    }
    .signout:hover {
      background: var(--color-surface-muted);
    }
    .signout:disabled {
      cursor: progress;
      opacity: 0.6;
    }
    .signout svg {
      width: 1.25rem;
      height: 1.25rem;
      stroke-width: 1.6;
    }
    @media (max-width: 39.99rem) {
      .trigger {
        padding-right: var(--space-1);
      }
      .name {
        position: absolute;
        width: 1px;
        height: 1px;
        clip-path: inset(50%);
      }
      .menu {
        top: 3.25rem;
      }
    }
    @supports (position-anchor: --account) {
      .menu {
        position-anchor: --account;
        top: calc(anchor(bottom) + var(--space-2));
        right: anchor(right);
      }
    }
    @media (prefers-reduced-motion: no-preference) {
      .menu {
        transform-origin: top right;
        transition:
          opacity var(--duration-base) var(--ease-out),
          transform var(--duration-base) var(--ease-out),
          overlay var(--duration-base) allow-discrete,
          display var(--duration-base) allow-discrete;
      }
      .menu:not(:popover-open) {
        opacity: 0;
        transform: translateY(-4px) scale(0.98);
      }
      @starting-style {
        .menu:popover-open {
          opacity: 0;
          transform: translateY(-4px) scale(0.98);
        }
      }
    }
  `,
})
export class AccountMenu {
  readonly me = input.required<Me>();
  /** True while signing out: the button is disabled and says so. */
  readonly busy = input(false);
  readonly signOut = output<void>();

  protected readonly initials = computed(() => initialsOf(this.me().name));
  protected readonly roleLabel = computed(() => roleLabelOf(this.me()));

  private readonly menu = viewChild.required<ElementRef<HTMLElement>>('menu');

  /** Closes the menu if it is open (browsers without popover support simply skip this). */
  close(): void {
    const menu = this.menu().nativeElement;
    if (typeof menu.hidePopover === 'function' && menu.matches(':popover-open')) {
      menu.hidePopover();
    }
  }
}

/** "Asha Test" → "AT"; a single name → its first letter. */
function initialsOf(name: string): string {
  const words = name
    .trim()
    .split(/\s+/)
    .filter((word) => word !== '');
  const picked = words.length > 1 ? [words[0], words[words.length - 1]] : words;
  return picked.map((word) => Array.from(word)[0].toUpperCase()).join('');
}

function roleLabelOf(me: Me): string {
  switch (me.role) {
    case 'ADMIN':
      return 'Admin';
    case 'TPO':
      return me.college ? `TPO, ${me.college.name}` : 'TPO';
    default:
      return 'Student';
  }
}
