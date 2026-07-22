import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AuthService } from './auth.service';
import { ApiService } from './api.service';
import { UsageStreamService } from './usage-stream.service';
import { Invoice, Session } from './models';

@Component({
    selector: 'app-root',
    imports: [
        CommonModule,
        ReactiveFormsModule,
        MatButtonModule,
        MatCardModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule
    ],
    templateUrl: './app.component.html',
    styleUrl: './app.component.scss',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AppComponent {
  private readonly formBuilder = inject(FormBuilder);
  readonly auth = inject(AuthService);
  readonly api = inject(ApiService);
  readonly usage = inject(UsageStreamService);

  readonly activeView = signal<'usage' | 'invoices'>('usage');
  readonly busy = signal(false);
  readonly error = signal('');
  readonly activity = signal('Ready');
  readonly invoices = signal<Invoice[]>([]);
  readonly selectedInvoice = computed(() => this.invoices()[0] ?? null);
  readonly loginForm = this.formBuilder.nonNullable.group({
    email: ['admin@meterly.local', [Validators.required, Validators.email]],
    password: ['meterly-demo', Validators.required]
  });

  login(): void {
    if (this.loginForm.invalid) return;
    this.busy.set(true);
    this.error.set('');
    this.auth.login(this.loginForm.controls.email.value, this.loginForm.controls.password.value)
      .subscribe({
        next: session => this.onAuthenticated(session),
        error: () => {
          this.busy.set(false);
          this.error.set('Those credentials were not accepted.');
        }
      });
  }

  emitUsage(): void {
    const session = this.auth.session();
    if (!session) return;
    this.activity.set('Publishing usage event…');
    this.api.emitUsage(session.ingestionApiKey).subscribe({
      next: () => this.activity.set('Event accepted — waiting for the live counter'),
      error: () => this.activity.set('Usage event could not be published')
    });
  }

  runBilling(): void {
    const now = new Date();
    const periodStart = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1));
    const periodEnd = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 1, 1));
    this.busy.set(true);
    this.api.runBilling(this.isoDate(periodStart), this.isoDate(periodEnd)).subscribe({
      next: invoice => {
        this.invoices.set([invoice, ...this.invoices().filter(existing => existing.id !== invoice.id)]);
        this.busy.set(false);
        this.activity.set(`Invoice ${invoice.id.slice(0, 8)} generated`);
      },
      error: () => {
        this.busy.set(false);
        this.activity.set('Invoice generation failed');
      }
    });
  }

  show(view: 'usage' | 'invoices'): void {
    this.activeView.set(view);
    if (view === 'invoices') this.refreshInvoices();
  }

  logout(): void {
    this.usage.disconnect();
    this.auth.logout();
    this.invoices.set([]);
  }

  private onAuthenticated(session: Session): void {
    this.usage.connect(session);
    this.api.currentUsage().subscribe({
      next: current => this.usage.seed(current.total_units),
      error: () => this.activity.set('Live stream connected; cached count unavailable')
    });
    this.refreshInvoices();
    this.busy.set(false);
  }

  private refreshInvoices(): void {
    this.api.listInvoices().subscribe({
      next: invoices => this.invoices.set(invoices),
      error: () => this.activity.set('Invoices are not available yet')
    });
  }

  private isoDate(date: Date): string {
    return date.toISOString().slice(0, 10);
  }
}

