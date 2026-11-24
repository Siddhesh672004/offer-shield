import { Routes } from '@angular/router';
import { roleGuard } from '../../core/auth/role.guard';
import { StudentHome } from './student-home';

/**
 * Lazy /app area (students; TPOs and admins can open it too). Uploading and reporting a scam
 * are for student accounts only (the API answers 403 otherwise), so those routes don't match
 * for other roles and fall through to "Page not found". `:id` binds to the components' `id`
 * input (`withComponentInputBinding`).
 */
export const studentRoutes: Routes = [
  { path: '', title: 'Your offers | OfferShield', component: StudentHome },
  {
    path: 'upload',
    title: 'Check an offer letter | OfferShield',
    canMatch: [roleGuard('USER')],
    loadComponent: () => import('./upload/upload').then((m) => m.Upload),
  },
  {
    path: 'submissions/:id',
    title: 'Risk report | OfferShield',
    loadComponent: () => import('./report/report-page').then((m) => m.ReportPage),
  },
  {
    path: 'submissions/:id/report-scam',
    title: 'Report a scam | OfferShield',
    canMatch: [roleGuard('USER')],
    loadComponent: () => import('./report-scam/report-scam').then((m) => m.ReportScam),
  },
];
