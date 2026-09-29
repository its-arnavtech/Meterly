import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, throwError } from 'rxjs';
import { AuthService } from '../services/auth.service';
import { SnackbarService } from '../services/snackbar.service';
import { SUPPRESS_GLOBAL_ERROR } from '../http/http-context.tokens';

const API_PREFIX = '/v1/';
/** Auth endpoints that must never trigger a refresh-and-retry (would loop or is nonsensical). */
const NO_REFRESH_RETRY = ['/v1/auth/login', '/v1/auth/refresh', '/v1/auth/logout', '/v1/tenants'];

function isApiRequest(url: string): boolean {
  return url.startsWith(API_PREFIX);
}

/**
 * Attaches `Authorization: Bearer <token>` to same-origin API calls. On a 401 from any
 * other API call, refreshes the access token once and retries; if that also fails, logs
 * out and sends the user to /login. All other errors surface as a snackbar unless the
 * caller opted out via SUPPRESS_GLOBAL_ERROR (e.g. a form rendering field errors inline).
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const snackbar = inject(SnackbarService);

  const token = auth.accessToken();
  const authedReq = token && isApiRequest(req.url) ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;

  return next(authedReq).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse)) {
        return throwError(() => error);
      }

      const eligibleForRefresh = error.status === 401 && isApiRequest(req.url) && !NO_REFRESH_RETRY.includes(req.url);
      if (eligibleForRefresh) {
        return auth.refresh().pipe(
          switchMap((res) => next(req.clone({ setHeaders: { Authorization: `Bearer ${res.access_token}` } }))),
          catchError((refreshError) => {
            auth.logout();
            router.navigate(['/login']);
            return throwError(() => refreshError);
          })
        );
      }

      if (!req.context.get(SUPPRESS_GLOBAL_ERROR)) {
        snackbar.showError(error);
      }
      return throwError(() => error);
    })
  );
};
