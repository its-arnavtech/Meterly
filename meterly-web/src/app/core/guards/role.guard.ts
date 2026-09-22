import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';

/**
 * Requires TENANT_ADMIN (docs/api-contract.md §0 roles). TENANT_USER is bounced back to
 * the dashboard rather than shown a dead end, since it's the one page every role can see.
 */
export const adminGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (auth.role() === 'TENANT_ADMIN') {
    return true;
  }
  return router.createUrlTree(['/dashboard']);
};
