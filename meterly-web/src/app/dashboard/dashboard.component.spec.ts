import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Subject, of } from 'rxjs';
import { DashboardComponent } from './dashboard.component';
import { AuthService } from '../core/services/auth.service';
import { UsageService } from '../core/services/usage.service';
import { BillingService } from '../core/services/billing.service';
import { UsageStompService } from '../core/services/usage-stomp.service';
import { UsageCurrent, UsageUpdate } from '../core/models';

describe('DashboardComponent', () => {
  let usageUpdates: Subject<UsageUpdate>;
  let usageServiceSpy: jasmine.SpyObj<UsageService>;
  let connectSpy: jasmine.Spy;
  let disconnectSpy: jasmine.Spy;

  const snapshot: UsageCurrent = {
    period_start: '2026-09-01',
    period_end: '2026-10-01',
    plan: { code: 'STARTER', name: 'Starter', included_units: 10000 },
    items: [
      {
        resource_id: 'res-1',
        resource_name: 'API calls',
        unit_label: 'call',
        total_units: 4200,
        included_units: 10000,
        percent_of_included: 42
      }
    ]
  };

  beforeEach(async () => {
    usageUpdates = new Subject<UsageUpdate>();
    connectSpy = jasmine.createSpy('connect');
    disconnectSpy = jasmine.createSpy('disconnect');

    usageServiceSpy = jasmine.createSpyObj<UsageService>('UsageService', ['current', 'timeseries']);
    usageServiceSpy.current.and.returnValue(of(snapshot));
    usageServiceSpy.timeseries.and.returnValue(
      of({ resource_id: 'res-1', granularity: 'day', from: '2026-09-01', to: '2026-09-22', points: [] })
    );

    const stompStub = {
      connectionState: signal('connected'),
      usageUpdates$: usageUpdates.asObservable(),
      connect: connectSpy,
      disconnect: disconnectSpy
    } as unknown as UsageStompService;

    const authStub = { role: signal('TENANT_ADMIN') } as unknown as AuthService;

    const billingSpy = jasmine.createSpyObj<BillingService>('BillingService', ['preview']);
    billingSpy.preview.and.returnValue(
      of({
        id: null,
        invoice_number: null,
        period_start: '2026-09-01',
        period_end: '2026-10-01',
        status: 'PREVIEW',
        currency: 'USD',
        total_amount: '19.00',
        issued_at: null,
        plan: { code: 'STARTER', name: 'Starter' },
        line_items: []
      })
    );

    await TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [
        provideNoopAnimations(),
        { provide: UsageService, useValue: usageServiceSpy },
        { provide: UsageStompService, useValue: stompStub },
        { provide: AuthService, useValue: authStub },
        { provide: BillingService, useValue: billingSpy }
      ]
    }).compileComponents();
  });

  it('loads the usage snapshot and renders a counter card', () => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    const component = fixture.componentInstance;
    expect(component.usageCurrent()?.items[0].total_units).toBe(4200);

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('API calls');
    expect(compiled.textContent).toContain('4,200');
  });

  it('connects to the STOMP feed on init and disconnects on destroy', () => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    expect(connectSpy).toHaveBeenCalledTimes(1);

    fixture.destroy();
    expect(disconnectSpy).toHaveBeenCalled();
  });

  it('updates the counter card and the live chart from a mocked STOMP payload', () => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance;

    const update: UsageUpdate = {
      tenant_id: 't1',
      resource_id: 'res-1',
      period_start: '2026-09-01',
      total_units: 4210,
      delta: 10,
      occurred_at: new Date().toISOString(),
      recorded_at: new Date().toISOString()
    };
    usageUpdates.next(update);
    fixture.detectChanges();

    // Counter card: authoritative total_units and recomputed percentage.
    const item = component.usageCurrent()?.items.find((i) => i.resource_id === 'res-1');
    expect(item?.total_units).toBe(4210);
    expect(item?.percent_of_included).toBeCloseTo(42.1, 5);

    // Live chart: one bucket carrying the delta.
    expect(component.liveChartData().length).toBe(1);
    expect(component.liveChartData()[0].series.length).toBe(1);
    expect(component.liveChartData()[0].series[0].value).toBe(10);

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('4,210');
  });

  it('ignores a STOMP update for a different billing period', () => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance;

    usageUpdates.next({
      tenant_id: 't1',
      resource_id: 'res-1',
      period_start: '2026-08-01', // not the displayed period
      total_units: 999,
      delta: 999,
      occurred_at: new Date().toISOString(),
      recorded_at: new Date().toISOString()
    });
    fixture.detectChanges();

    const item = component.usageCurrent()?.items.find((i) => i.resource_id === 'res-1');
    expect(item?.total_units).toBe(4200);
    expect(component.liveChartData()[0]?.series.length ?? 0).toBe(0);
  });
});
