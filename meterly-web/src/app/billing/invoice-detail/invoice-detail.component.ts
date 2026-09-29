import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { BillingService } from '../../core/services/billing.service';
import { MoneyPipe } from '../../shared/pipes/money.pipe';
import { InvoiceDetail } from '../../core/models';

@Component({
  selector: 'app-invoice-detail',
  standalone: true,
  imports: [DatePipe, DecimalPipe, RouterLink, MatTableModule, MatButtonModule, MatIconModule, MatChipsModule, MoneyPipe],
  templateUrl: './invoice-detail.component.html',
  styleUrl: './invoice-detail.component.scss'
})
export class InvoiceDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly billing = inject(BillingService);

  readonly invoice = signal<InvoiceDetail | null>(null);
  readonly loading = signal(true);
  readonly notFound = signal(false);
  readonly lineItemColumns = ['line_number', 'description', 'quantity', 'unit_price', 'amount'];

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.notFound.set(true);
      this.loading.set(false);
      return;
    }
    this.billing.invoice(id).subscribe({
      next: (invoice) => {
        this.invoice.set(invoice);
        this.loading.set(false);
      },
      error: () => {
        this.notFound.set(true);
        this.loading.set(false);
      }
    });
  }

  print(): void {
    window.print();
  }
}
