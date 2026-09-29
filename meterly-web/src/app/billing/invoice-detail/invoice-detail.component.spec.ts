import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { InvoiceDetailComponent } from './invoice-detail.component';
import { BillingService } from '../../core/services/billing.service';
import { InvoiceDetail } from '../../core/models';

describe('InvoiceDetailComponent', () => {
  const invoice: InvoiceDetail = {
    id: 'inv-1',
    invoice_number: 'INV-202608-000001',
    period_start: '2026-08-01',
    period_end: '2026-09-01',
    status: 'ISSUED',
    currency: 'USD',
    total_amount: '278.69',
    issued_at: '2026-09-02T02:00:00Z',
    plan: { code: 'STARTER', name: 'Starter' },
    line_items: [
      { line_number: 1, description: 'Starter plan — base fee', resource_id: null, quantity: 1, unit_price: '19.000000', amount: '19.00' },
      {
        line_number: 2,
        description: 'API calls — included (1–10,000)',
        resource_id: 'res-1',
        quantity: 10000,
        unit_price: '0.000000',
        amount: '0.00'
      },
      {
        line_number: 3,
        description: 'API calls — 10,001–100,000',
        resource_id: 'res-1',
        quantity: 90000,
        unit_price: '0.002000',
        amount: '180.00'
      }
    ]
  };

  function setup(id: string | null, billingSpy: jasmine.SpyObj<BillingService>) {
    return TestBed.configureTestingModule({
      imports: [InvoiceDetailComponent],
      providers: [
        provideRouter([]),
        provideNoopAnimations(),
        { provide: BillingService, useValue: billingSpy },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap(id ? { id } : {}) } } }
      ]
    }).compileComponents();
  }

  it('renders the plan snapshot, period, status, and every line item', async () => {
    const billingSpy = jasmine.createSpyObj<BillingService>('BillingService', ['invoice']);
    billingSpy.invoice.and.returnValue(of(invoice));
    await setup('inv-1', billingSpy);

    const fixture = TestBed.createComponent(InvoiceDetailComponent);
    fixture.detectChanges();

    expect(billingSpy.invoice).toHaveBeenCalledWith('inv-1');
    const component = fixture.componentInstance;
    expect(component.invoice()?.line_items.length).toBe(3);

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('INV-202608-000001');
    expect(text).toContain('Starter');
    expect(text).toContain('ISSUED');
    expect(text).toContain('Starter plan — base fee');
    expect(text).toContain('API calls — included (1–10,000)');
    expect(text).toContain('API calls — 10,001–100,000');
    expect(text).toContain('$278.69');
    // Line item table renders each amount via the money pipe.
    expect(text).toContain('$19.00');
    expect(text).toContain('$180.00');
  });

  it('shows a not-found state on a 404', async () => {
    const billingSpy = jasmine.createSpyObj<BillingService>('BillingService', ['invoice']);
    billingSpy.invoice.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 404, error: { code: 'INVOICE_NOT_FOUND' } }))
    );
    await setup('missing', billingSpy);

    const fixture = TestBed.createComponent(InvoiceDetailComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.notFound()).toBeTrue();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Invoice not found');
  });
});
