import {HttpInterceptorFn} from '@angular/common/http';
import {inject} from '@angular/core';
import {AuthService} from './auth.service';

export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const session = inject(AuthService).session();
  if (!session || request.url.includes('/v1/auth/login') || request.url.includes(':8181/')) return next(request);
  return next(request.clone({setHeaders: {Authorization: `Bearer ${session.accessToken}`}}));
};
