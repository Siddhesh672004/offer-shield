import { Component } from '@angular/core';
import { Routes } from '@angular/router';

/** Placeholder until P12-04 (colleges and TPOs, scam reports, known companies). */
@Component({
  selector: 'app-admin-home',
  template: `
    <h1>Admin</h1>
    <p class="lead">
      Colleges, TPO assignments and scam reports waiting for review will appear here.
    </p>
  `,
})
export class AdminHome {}

/** Lazy /admin area; `roleGuard('ADMIN')` decides whether it matches. */
export const adminRoutes: Routes = [
  { path: '', title: 'Admin | OfferShield', component: AdminHome },
];
