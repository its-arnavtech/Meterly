import {CommonModule} from '@angular/common';
import {ChangeDetectionStrategy, Component, inject, signal} from '@angular/core';
import {MatButtonModule} from '@angular/material/button';
import {RouterLink} from '@angular/router';
import {ApiService} from '../core/api.service';
import {Invoice, Plan} from '../core/models';

@Component({
  selector: 'app-invoice-list', standalone: true, imports: [CommonModule, MatButtonModule, RouterLink],
  template: `
    <section class="page-heading"><div><p>BILLING</p><h1>Invoices</h1></div><button mat-flat-button (click)="runBilling()" [disabled]="busy()">Run current period</button></section>
    @if (plan(); as currentPlan) {
      <section class="plan"><div><span>CURRENT PLAN</span><strong>{{ currentPlan.name }}</strong></div>
        <div><span>BASE</span><strong>{{ currentPlan.basePrice | currency }}</strong></div>
        <div><span>INCLUDED</span><strong>{{ currentPlan.includedUnits | number }}</strong></div>
        <div><span>TIERS</span>@for (tier of currentPlan.tiers; track tier.fromUnit) { <small>{{ tier.fromUnit | number }}–{{ tier.toUnit ? (tier.toUnit | number) : '∞' }} · {{ tier.unitPrice | currency:'USD':'symbol':'1.2-6' }}</small> }</div>
      </section>
    }
    <section class="invoice-list">
      @for (invoice of invoices(); track invoice.id) {
        <a [routerLink]="[invoice.id]"><div><span>#{{ invoice.id.slice(0, 8).toUpperCase() }}</span><small>{{ invoice.periodStart | date }} – {{ invoice.periodEnd | date }}</small></div><strong>{{ invoice.totalAmount | currency }}</strong></a>
      } @empty { <div class="empty">No invoices have been generated.</div> }
    </section>
  `,
  styles: [`
    .page-heading{display:flex;align-items:end;justify-content:space-between;margin-bottom:30px}.page-heading p,.plan span{margin:0;color:#0f766e;font-size:.7rem;font-weight:800;letter-spacing:.14em}h1{margin:7px 0 0;font-size:2.5rem}.plan{display:grid;grid-template-columns:repeat(4,1fr);gap:20px;margin-bottom:18px;padding:24px;border:1px solid #dce7e7;border-radius:20px;background:#fff}.plan div{display:flex;flex-direction:column;gap:6px}.plan small{color:#64748b}.invoice-list{border:1px solid #dce7e7;border-radius:20px;background:#fff;overflow:hidden}.invoice-list a{display:flex;justify-content:space-between;padding:24px;color:inherit;text-decoration:none;border-bottom:1px solid #eef2f2}.invoice-list a div{display:flex;flex-direction:column;gap:5px}.invoice-list small{color:#64748b}.empty{padding:70px;text-align:center;color:#64748b}@media(max-width:800px){.plan{grid-template-columns:1fr 1fr}}
  `], changeDetection: ChangeDetectionStrategy.OnPush
})
export class InvoiceListComponent {
  private readonly api = inject(ApiService); readonly invoices = signal<Invoice[]>([]); readonly plan = signal<Plan | null>(null); readonly busy = signal(false);
  constructor() { this.refresh(); this.api.currentPlan().subscribe(plan => this.plan.set(plan)); }
  runBilling(): void {
    const now = new Date(); const start = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1)); const end = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 1, 1));
    this.busy.set(true); this.api.runBilling(this.iso(start), this.iso(end)).subscribe({next: invoice => { this.invoices.update(items => [invoice, ...items.filter(item => item.id !== invoice.id)]); this.busy.set(false); }, error: () => this.busy.set(false)});
  }
  private refresh(): void { this.api.listInvoices().subscribe(invoices => this.invoices.set(invoices)); }
  private iso(date: Date): string { return date.toISOString().slice(0, 10); }
}
