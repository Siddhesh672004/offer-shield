import { Routes } from '@angular/router';

/**
 * Lazy /admin area; `roleGuard('ADMIN')` decides whether it matches. The server still checks
 * every call (ADMIN only). `?status=` binds to the scam-report page's `status` input.
 */
export const adminRoutes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'colleges' },
  {
    path: 'colleges',
    title: 'Colleges and TPOs | OfferShield',
    loadComponent: () => import('./colleges/admin-colleges').then((m) => m.AdminColleges),
  },
  {
    path: 'scam-reports',
    title: 'Scam reports | OfferShield',
    loadComponent: () =>
      import('./scam-reports/admin-scam-reports').then((m) => m.AdminScamReports),
  },
  {
    path: 'companies',
    title: 'Known companies | OfferShield',
    loadComponent: () => import('./companies/known-companies').then((m) => m.KnownCompanies),
  },
];
