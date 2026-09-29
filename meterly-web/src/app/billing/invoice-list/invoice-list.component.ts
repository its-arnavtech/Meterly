import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { HttpContext } from '@angular/common/http';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { BillingService } from '../../core/services/billing.service';
import { SnackbarService } from '../../core/services/snackbar.service';
import { SUPPRESS_GLOBAL_ERROR } from '../../core/http/http-context.tokens';
import { problemOf } from '../../shared/utils/problem-detail.util';
import { MoneyPipe } from '../../shared/pipes/money.pipe';
import { InvoiceSummary } from '../../core/models';

@Component({
  selector: 'app-invoice-list',
  standalone: true,
  imports: [
    DatePipe,
    RouterLink,
    MatTableModule,
    MatButtonModule,
    MatIconModule,
    MatChipsModule,
    MatProgressSpinnerModule,
    MoneyPipe
  ],
  templateUrl: './invoice-list.component.html',
  styleUrl: './invoice-list.component.scss'
})
export class InvoiceListComponent implements OnInit {
  private readonly billing = inject(BillingService);
  private readonly snackbar = inject(SnackbarService);

  readonly invoices = signal<InvoiceSummary[]>([]);
  readonly loading = signal(true);
  readonly running = signal(false);
  readonly columns = ['invoice_number', 'period_start', 'status', 'total_amount', 'issued_at', 'actions'];

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.billing.invoices().subscribe({
      next: (items) => {
        this.invoices.set(items);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  runBilling(): void {
    if (this.running()) {
      return;
    }
    this.running.set(true);
    const context = new HttpContext().set(SUPPRESS_GLOBAL_ERROR, true);
    this.billing.run({}, context).subscribe({
      next: (result) => {
        this.running.set(false);
        this.snackbar.showInfo(
          result.created
            ? `Invoice ${result.invoice.invoice_number} generated.`
            : `Invoice ${result.invoice.invoice_number} already existed for that period.`
        );
        this.load();
      },
      error: (err) => {
        this.running.set(false);
        const problem = problemOf(err);
        if (problem?.code === 'PERIOD_NOT_CLOSED') {
          this.snackbar.showError('That billing period has not closed yet.');
        } else if (problem?.code === 'NO_SUBSCRIPTION') {
          this.snackbar.showError('No subscription covers that period.');
        } else {
          this.snackbar.showError(problem?.detail || 'Unable to generate the invoice.');
        }
      }
    });
  }
}
