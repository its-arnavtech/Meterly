import {Routes} from '@angular/router';
import {authenticatedGuard, tenantAdminGuard} from './core/auth.guards';

export const routes: Routes = [
  {path: 'login', loadChildren: () => import('./auth/auth.module').then(module => module.AuthModule)},
  {path: 'dashboard', canActivate: [authenticatedGuard], loadChildren: () => import('./dashboard/dashboard.module').then(module => module.DashboardModule)},
  {path: 'billing', canActivate: [authenticatedGuard, tenantAdminGuard], loadChildren: () => import('./billing/billing.module').then(module => module.BillingModule)},
  {path: '', pathMatch: 'full', redirectTo: 'dashboard'},
  {path: '**', redirectTo: 'dashboard'}
];
