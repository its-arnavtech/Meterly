import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {Router} from '@angular/router';
import {AuthService} from './auth.service';
import {tenantAdminGuard} from './auth.guards';
import {Session} from './models';

describe('tenantAdminGuard', () => {
  const session = signal<Session | null>(null);
  const redirected = {redirect: true};
  beforeEach(() => TestBed.configureTestingModule({providers: [
    {provide: AuthService, useValue: {session}},
    {provide: Router, useValue: {createUrlTree: () => redirected}}
  ]}));
  it('allows TENANT_ADMIN', () => {
    session.set({accessToken: 'token', tenantId: 'tenant', email: 'admin@test', role: 'TENANT_ADMIN'});
    expect(TestBed.runInInjectionContext(() => tenantAdminGuard({} as never, {} as never))).toBeTrue();
  });
  it('redirects TENANT_USER away from billing', () => {
    session.set({accessToken: 'token', tenantId: 'tenant', email: 'user@test', role: 'TENANT_USER'});
    expect(TestBed.runInInjectionContext(() => tenantAdminGuard({} as never, {} as never))).toBe(redirected as never);
  });
});
