import { HttpRequest, HttpResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';
import { Session } from './models';

describe('authInterceptor', () => {
  const session: Session = {
    accessToken: 'signed-token',
    tenantId: '11111111-1111-1111-1111-111111111111',
    email: 'admin@meterly.local',
    role: 'TENANT_ADMIN',
    ingestionApiKey: 'meterly-poc-api-key'
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [{provide: AuthService, useValue: {session: signal(session)}}]
    });
  });

  it('does not leak the JWT to the API-key ingestion service', () => {
    const request = new HttpRequest('POST', 'http://127.0.0.1:8181/v1/usage-events', null);
    let forwarded: HttpRequest<unknown> | undefined;

    TestBed.runInInjectionContext(() => authInterceptor(request, candidate => {
      forwarded = candidate;
      return of(new HttpResponse());
    }).subscribe());

    expect(forwarded?.headers.has('Authorization')).toBeFalse();
  });

  it('adds the JWT to authenticated billing requests', () => {
    const request = new HttpRequest('GET', 'http://127.0.0.1:8082/v1/invoices');
    let forwarded: HttpRequest<unknown> | undefined;

    TestBed.runInInjectionContext(() => authInterceptor(request, candidate => {
      forwarded = candidate;
      return of(new HttpResponse());
    }).subscribe());

    expect(forwarded?.headers.get('Authorization')).toBe('Bearer signed-token');
  });
});
