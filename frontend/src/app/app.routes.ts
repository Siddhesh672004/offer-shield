import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';
import { choosePathGuard } from './core/auth/path-choice';
import { roleGuard } from './core/auth/role.guard';
import { NotFound } from './features/not-found/not-found';

/**
 * Guards only choose which screens to show. Every `/api` call is still checked on the server
 * (session identity, role, college scoping).
 */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'app' },
  {
    path: 'app',
    canMatch: [authGuard, choosePathGuard],
    loadChildren: () => import('./features/student/student.routes').then((m) => m.studentRoutes),
  },
  {
    path: 'tpo',
    canMatch: [authGuard, roleGuard('TPO')],
    loadChildren: () => import('./features/tpo/tpo.routes').then((m) => m.tpoRoutes),
  },
  {
    path: 'admin',
    canMatch: [authGuard, roleGuard('ADMIN')],
    loadChildren: () => import('./features/admin/admin.routes').then((m) => m.adminRoutes),
  },
  { path: '**', title: 'Page not found | OfferShield', component: NotFound },
];
