import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { AppComponent } from './app.component';
import { AuthService } from './auth.service';
import { ApiService } from './api.service';
import { UsageStreamService } from './usage-stream.service';
import { Session } from './models';

describe('AppComponent', () => {
  const session = signal<Session | null>(null);
  const totalUnits = signal(0);
  const connected = signal(false);
  const auth = {
    session,
    login: () => of({
      accessToken: 'token',
      tenantId: '11111111-1111-1111-1111-111111111111',
      email: 'admin@meterly.local',
      role: 'TENANT_ADMIN',
      ingestionApiKey: 'meterly-poc-api-key'
    }),
    logout: () => session.set(null)
  };
  const usage = {
    totalUnits,
    connected,
    connect: () => connected.set(true),
    disconnect: () => connected.set(false),
    seed: (total: number) => totalUnits.set(total)
  };
  const api = {
    currentUsage: () => of({total_units: 0}),
    listInvoices: () => of([]),
    emitUsage: () => of({}),
    runBilling: () => of({})
  };

  beforeEach(async () => {
    session.set(null);
    totalUnits.set(0);
    connected.set(false);
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [
        provideNoopAnimations(),
        {provide: AuthService, useValue: auth},
        {provide: UsageStreamService, useValue: usage},
        {provide: ApiService, useValue: api}
      ]
    }).compileComponents();
  });

  it('renders the seeded-admin login view', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Welcome back');
    expect(fixture.nativeElement.querySelector('input[type=email]').value).toBe('admin@meterly.local');
  });

  it('renders a live counter update without polling', () => {
    session.set({
      accessToken: 'token',
      tenantId: '11111111-1111-1111-1111-111111111111',
      email: 'admin@meterly.local',
      role: 'TENANT_ADMIN',
      ingestionApiKey: 'meterly-poc-api-key'
    });
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();

    usage.seed(1375);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid=usage-total]').textContent).toContain('1,375');
  });
});

