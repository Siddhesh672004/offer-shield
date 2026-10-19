import { Routes } from '@angular/router';
import { StudentHome } from './student-home';

/** Lazy /app area (students; TPOs and admins can open it too). Upload and reports arrive in P12. */
export const studentRoutes: Routes = [
  { path: '', title: 'Your offers | OfferShield', component: StudentHome },
];
