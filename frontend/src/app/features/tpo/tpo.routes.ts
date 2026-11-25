import { Routes } from '@angular/router';

/**
 * Lazy /tpo area; `roleGuard('TPO')` decides whether it matches. The server still checks every
 * call (TPO role, college from the database). `:id` binds to the case page's `id` input.
 */
export const tpoRoutes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'members' },
  {
    path: 'members',
    title: 'Pending members | OfferShield',
    loadComponent: () => import('./members/pending-members').then((m) => m.PendingMembers),
  },
  {
    path: 'reviews',
    title: 'Review queue | OfferShield',
    loadComponent: () => import('./reviews/review-queue').then((m) => m.ReviewQueue),
  },
  {
    path: 'reviews/:id',
    title: 'Review an offer | OfferShield',
    loadComponent: () => import('./reviews/review-case').then((m) => m.ReviewCase),
  },
];
