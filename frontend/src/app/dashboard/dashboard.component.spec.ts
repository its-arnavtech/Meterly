import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {provideNoopAnimations} from '@angular/platform-browser/animations';
import {of} from 'rxjs';
import {ApiService} from '../core/api.service';
import {AuthService} from '../core/auth.service';
import {Session} from '../core/models';
import {UsageStreamService} from '../core/usage-stream.service';
import {DashboardComponent} from './dashboard.component';

describe('DashboardComponent', () => {
  it('renders a live STOMP total for a real tenant resource', async () => {
    const session = signal<Session | null>({accessToken: 'token', tenantId: 'tenant', email: 'user@test', role: 'TENANT_USER'});
    const totalUnits = signal(0); const connected = signal(true);
    await TestBed.configureTestingModule({imports: [DashboardComponent], providers: [
      provideNoopAnimations(),
      {provide: AuthService, useValue: {session}},
      {provide: UsageStreamService, useValue: {totalUnits, connected, connect: () => undefined, seed: (total: number) => totalUnits.set(total)}},
      {provide: ApiService, useValue: {resources: () => of([{id: 'resource', name: 'API calls', unitLabel: 'calls'}]), currentUsage: () => of({total_units: 1375})}}
    ]}).compileComponents();
    const fixture = TestBed.createComponent(DashboardComponent); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid=usage-total]').textContent).toContain('1,375');
    expect(fixture.componentInstance.chartData()).toEqual([{name: 'API calls', value: 1375}]);
    expect(fixture.nativeElement.querySelector('[data-testid=usage-chart]')).not.toBeNull();
  });
});
