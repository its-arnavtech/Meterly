import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {AppComponent} from './app.component';
import {AuthService} from './core/auth.service';
import {Session} from './core/models';
import {UsageStreamService} from './core/usage-stream.service';

describe('AppComponent', () => {
  const session = signal<Session | null>(null);
  beforeEach(async () => {
    await TestBed.configureTestingModule({imports: [AppComponent], providers: [provideRouter([]),
      {provide: AuthService, useValue: {session, logout: () => session.set(null)}},
      {provide: UsageStreamService, useValue: {disconnect: () => undefined}}]}).compileComponents();
  });
  it('shows billing navigation only to a tenant administrator', () => {
    session.set({accessToken: 'token', tenantId: 'tenant', email: 'admin@test', role: 'TENANT_ADMIN'});
    const fixture = TestBed.createComponent(AppComponent); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Billing');
    session.set({...session()!, role: 'TENANT_USER'}); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('Billing');
  });
});
