import { Routes } from '@angular/router';
import { authGuard, guestGuard } from './core/guards/auth.guard';
import { adminGuard } from './core/guards/role.guard';

export const routes: Routes = [
  {
    path: 'login',
    canActivate: [guestGuard],
    loadComponent: () => import('./auth/login/login.component').then((m) => m.LoginComponent)
  },
  {
    path: 'signup',
    canActivate: [guestGuard],
    loadComponent: () => import('./auth/signup/signup.component').then((m) => m.SignupComponent)
  },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./layout/shell/shell.component').then((m) => m.ShellComponent),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
      {
        path: 'dashboard',
        loadComponent: () => import('./dashboard/dashboard.component').then((m) => m.DashboardComponent)
      },
      {
        path: 'billing',
        canActivate: [adminGuard],
        loadComponent: () =>
          import('./billing/billing-shell/billing-shell.component').then((m) => m.BillingShellComponent),
        children: [
          { path: '', pathMatch: 'full', redirectTo: 'invoices' },
          {
            path: 'invoices',
            loadComponent: () =>
              import('./billing/invoice-list/invoice-list.component').then((m) => m.InvoiceListComponent)
          },
          {
            path: 'invoices/:id',
            loadComponent: () =>
              import('./billing/invoice-detail/invoice-detail.component').then((m) => m.InvoiceDetailComponent)
          },
          {
            path: 'plan',
            loadComponent: () => import('./billing/plan/plan.component').then((m) => m.PlanComponent)
          }
        ]
      },
      {
        path: 'admin',
        canActivate: [adminGuard],
        loadComponent: () =>
          import('./admin/admin-shell/admin-shell.component').then((m) => m.AdminShellComponent),
        children: [
          { path: '', pathMatch: 'full', redirectTo: 'api-keys' },
          {
            path: 'api-keys',
            loadComponent: () => import('./admin/api-keys/api-keys.component').then((m) => m.ApiKeysComponent)
          },
          {
            path: 'users',
            loadComponent: () => import('./admin/users/users.component').then((m) => m.UsersComponent)
          },
          {
            path: 'resources',
            loadComponent: () =>
              import('./admin/resources/resources.component').then((m) => m.ResourcesComponent)
          },
          {
            path: 'notifications',
            loadComponent: () =>
              import('./admin/notifications/notifications.component').then((m) => m.NotificationsComponent)
          }
        ]
      }
    ]
  },
  { path: '**', redirectTo: 'dashboard' }
];
