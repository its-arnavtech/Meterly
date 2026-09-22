import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe, DecimalPipe } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatChipsModule } from '@angular/material/chips';
import { NgxChartsModule } from '@swimlane/ngx-charts';

import { AuthService } from '../core/services/auth.service';
import { UsageService } from '../core/services/usage.service';
import { BillingService } from '../core/services/billing.service';
import { UsageStompService } from '../core/services/usage-stomp.service';
import { InvoiceDetail, UsageCurrent, UsageItem, UsageUpdate } from '../core/models';
import { MoneyPipe } from '../shared/pipes/money.pipe';
import { applyDeltaToBuckets, LiveBucket, toLineChartSeries, NgxChartSeries } from './live-series.util';
import { SendTestEventDialogComponent } from './send-test-event-dialog.component';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [
    DecimalPipe,
    DatePipe,
    MatCardModule,
    MatProgressBarModule,
    MatIconModule,
    MatSelectModule,
    MatFormFieldModule,
    MatButtonModule,
    MatChipsModule,
    NgxChartsModule,
    MoneyPipe
  ],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss'
})
export class DashboardComponent implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly usageService = inject(UsageService);
  private readonly billingService = inject(BillingService);
  private readonly stomp = inject(UsageStompService);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);

  readonly isAdmin = computed(() => this.auth.role() === 'TENANT_ADMIN');
  readonly connectionState = this.stomp.connectionState;

  readonly loading = signal(true);
  readonly usageCurrent = signal<UsageCurrent | null>(null);
  readonly selectedResourceId = signal<string | null>(null);
  readonly dailySeries = signal<NgxChartSeries[]>([]);
  readonly billingPreview = signal<InvoiceDetail | null>(null);

  private liveBucketsByResource = new Map<string, LiveBucket[]>();
  readonly liveChartData = signal<NgxChartSeries[]>([]);

  readonly selectedItem = computed<UsageItem | null>(() => {
    const current = this.usageCurrent();
    const id = this.selectedResourceId();
    return current?.items.find((i) => i.resource_id === id) ?? null;
  });

  ngOnInit(): void {
    this.loadSnapshot();

    this.stomp.connect();
    this.stomp.usageUpdates$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((update) => this.applyUpdate(update));
    this.destroyRef.onDestroy(() => this.stomp.disconnect());

    if (this.isAdmin()) {
      this.billingService.preview().subscribe({
        next: (preview) => this.billingPreview.set(preview),
        error: () => this.billingPreview.set(null)
      });
    }
  }

  selectResource(resourceId: string): void {
    this.selectedResourceId.set(resourceId);
    this.refreshDailySeries();
    this.refreshLiveChart();
  }

  openSendTestEvent(): void {
    const items = this.usageCurrent()?.items ?? [];
    if (!items.length) {
      return;
    }
    this.dialog.open(SendTestEventDialogComponent, { data: { resources: items }, width: '420px' });
  }

  progressColor(percent: number): 'primary' | 'accent' | 'warn' {
    if (percent >= 100) {
      return 'warn';
    }
    if (percent >= 80) {
      return 'accent';
    }
    return 'primary';
  }

  private loadSnapshot(): void {
    this.usageService.current().subscribe({
      next: (current) => {
        this.usageCurrent.set(current);
        this.loading.set(false);
        if (!this.selectedResourceId() && current.items.length) {
          this.selectResource(current.items[0].resource_id);
        }
      },
      error: () => this.loading.set(false)
    });
  }

  private refreshDailySeries(): void {
    const resourceId = this.selectedResourceId();
    if (!resourceId) {
      return;
    }
    this.usageService.timeseries({ resource_id: resourceId, granularity: 'day' }).subscribe({
      next: (series) => {
        this.dailySeries.set([
          {
            name: 'Daily units',
            series: series.points.map((p) => ({ name: new Date(p.bucket_start), value: p.units }))
          }
        ]);
      },
      error: () => this.dailySeries.set([])
    });
  }

  private refreshLiveChart(): void {
    const resourceId = this.selectedResourceId();
    const item = this.selectedItem();
    if (!resourceId || !item) {
      this.liveChartData.set([]);
      return;
    }
    const buckets = this.liveBucketsByResource.get(resourceId) ?? [];
    this.liveChartData.set(toLineChartSeries(item.resource_name, buckets));
  }

  /** Applies a live STOMP update: authoritative counter total, plus a rolling live-chart bucket. */
  private applyUpdate(update: UsageUpdate): void {
    const current = this.usageCurrent();
    if (!current || update.period_start !== current.period_start) {
      return; // ignore updates for a period we're not displaying
    }
    const items = current.items.map((item) =>
      item.resource_id === update.resource_id
        ? {
            ...item,
            total_units: update.total_units,
            percent_of_included: item.included_units > 0 ? (update.total_units / item.included_units) * 100 : 0
          }
        : item
    );
    this.usageCurrent.set({ ...current, items });

    const occurredAtMs = Date.parse(update.occurred_at);
    const existing = this.liveBucketsByResource.get(update.resource_id) ?? [];
    this.liveBucketsByResource.set(update.resource_id, applyDeltaToBuckets(existing, occurredAtMs, update.delta));

    if (update.resource_id === this.selectedResourceId()) {
      this.refreshLiveChart();
    }
  }
}
