import {inject} from '@angular/core';
import {CanActivateFn, Router} from '@angular/router';
import {AuthService} from './auth.service';

export const authenticatedGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  return auth.session() ? true : inject(Router).createUrlTree(['/login']);
};

export const tenantAdminGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  if (!auth.session()) return inject(Router).createUrlTree(['/login']);
  return auth.session()?.role === 'TENANT_ADMIN' ? true : inject(Router).createUrlTree(['/dashboard']);
};
