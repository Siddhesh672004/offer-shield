import { Component, computed, inject } from '@angular/core';
import { Routes } from '@angular/router';
import { Session } from '../../core/auth/session';

/** Placeholder until P12-03 (pending members, review queue). */
@Component({
  selector: 'app-tpo-home',
  template: `
    <h1>TPO desk</h1>
    <p class="lead">Join requests and review cases for {{ collegeName() }} will appear here.</p>
  `,
})
export class TpoHome {
  private readonly session = inject(Session);

  protected readonly collegeName = computed(
    () => this.session.me()?.college?.name ?? 'your college',
  );
}

/** Lazy /tpo area; `roleGuard('TPO')` decides whether it matches. */
export const tpoRoutes: Routes = [
  { path: '', title: 'TPO desk | OfferShield', component: TpoHome },
];
