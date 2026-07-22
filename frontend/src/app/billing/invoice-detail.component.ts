import {CommonModule} from '@angular/common';
import {ChangeDetectionStrategy, Component, inject, signal} from '@angular/core';
import {ActivatedRoute, RouterLink} from '@angular/router';
import {ApiService} from '../core/api.service';
import {Invoice} from '../core/models';

@Component({
  selector: 'app-invoice-detail', standalone: true, imports: [CommonModule, RouterLink],
  template: `
    <a routerLink="/billing" class="back">← All invoices</a>
    @if (invoice(); as item) {
      <section class="invoice"><header><div><span>INVOICE</span><strong>#{{ item.id.slice(0, 8).toUpperCase() }}</strong></div><div><span>PERIOD</span><strong>{{ item.periodStart | date }} – {{ item.periodEnd | date }}</strong></div><b>{{ item.status }}</b></header>
        <div class="row labels"><span>Description</span><span>Qty</span><span>Unit price</span><span>Amount</span></div>
        @for (line of item.lineItems; track line.id) { <div class="row"><span>{{ line.description }}</span><span>{{ line.quantity | number }}</span><span>{{ line.unitPrice | currency:'USD':'symbol':'1.2-6' }}</span><span>{{ line.amount | currency }}</span></div> }
        <footer><span>Total</span><strong>{{ item.totalAmount | currency }}</strong></footer>
      </section>
    } @else { <p>{{ error() || 'Loading invoice…' }}</p> }
  `,
  styles: [`
    .back{display:inline-block;margin-bottom:20px;color:#0f766e;text-decoration:none}.invoice{padding:32px;border:1px solid #dce7e7;border-radius:20px;background:#fff}.invoice header{display:grid;grid-template-columns:1fr 2fr auto;gap:28px;padding-bottom:28px}.invoice header div{display:flex;flex-direction:column}.invoice header span,.labels{color:#64748b;font-size:.7rem;font-weight:750;letter-spacing:.08em}.invoice header b{color:#047857}.row{display:grid;grid-template-columns:1fr 90px 130px 120px;gap:18px;padding:18px 10px;border-top:1px solid #eef2f2}.row span:not(:first-child){text-align:right}.invoice footer{display:flex;justify-content:flex-end;gap:32px;padding:28px 10px;border-top:2px solid #0f172a}.invoice footer strong{font-size:2rem}
  `], changeDetection: ChangeDetectionStrategy.OnPush
})
export class InvoiceDetailComponent {
  private readonly api = inject(ApiService); private readonly route = inject(ActivatedRoute);
  readonly invoice = signal<Invoice | null>(null); readonly error = signal('');
  constructor() { const id = this.route.snapshot.paramMap.get('id'); if (id) this.api.getInvoice(id).subscribe({next: invoice => this.invoice.set(invoice), error: () => this.error.set('Invoice not found.')}); }
}
