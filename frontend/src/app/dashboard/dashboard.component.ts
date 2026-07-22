import {CommonModule} from '@angular/common';
import {ChangeDetectionStrategy, Component, inject, signal} from '@angular/core';
import {ApiService} from '../core/api.service';
import {AuthService} from '../core/auth.service';
import {Resource} from '../core/models';
import {UsageStreamService} from '../core/usage-stream.service';

@Component({
  selector: 'app-dashboard', standalone: true, imports: [CommonModule],
  template: `
    <section class="page-heading"><div><p>CURRENT BILLING PERIOD</p><h1>API usage</h1></div>
      <div class="live-pill" [class.online]="usage.connected()"><i></i>{{ usage.connected() ? 'Live' : 'Connecting' }}</div></section>
    @if (resource(); as current) {
      <section class="metric-grid"><article class="hero-metric"><span>{{ current.name }}</span>
        <strong data-testid="usage-total">{{ usage.totalUnits() | number }}</strong><p>{{ current.unitLabel }} this month</p></article>
        <article><span>Access</span><strong>Tenant isolated</strong><p>{{ auth.session()?.role }}</p></article>
        <article><span>Pipeline</span><strong>Kafka → Redis → STOMP</strong><p>Idempotent live delivery</p></article></section>
    } @else { <section class="empty">{{ error() || 'Loading tenant resources…' }}</section> }
  `,
  styles: [`
    .page-heading{display:flex;align-items:end;justify-content:space-between;margin-bottom:30px}.page-heading p{margin:0;color:#0f766e;font-size:.72rem;font-weight:800;letter-spacing:.16em}h1{margin:7px 0 0;font-size:2.5rem}.live-pill{display:flex;align-items:center;gap:8px;color:#92400e;background:#fef3c7;border-radius:99px;padding:8px 13px;font-size:.72rem;font-weight:800}.live-pill i{width:7px;height:7px;border-radius:50%;background:currentColor}.live-pill.online{color:#047857;background:#d1fae5}.metric-grid{display:grid;grid-template-columns:2fr 1fr 1fr;gap:18px}.metric-grid article,.empty{min-height:220px;padding:28px;border:1px solid #dce7e7;border-radius:20px;background:#fff}.metric-grid article{display:flex;flex-direction:column}.metric-grid span{color:#64748b;font-size:.8rem;font-weight:750}.metric-grid strong{margin:auto 0 0;font-size:1.35rem}.hero-metric strong{font-size:4.3rem;letter-spacing:-.06em}.metric-grid p{color:#64748b}@media(max-width:900px){.metric-grid{grid-template-columns:1fr}}
  `], changeDetection: ChangeDetectionStrategy.OnPush
})
export class DashboardComponent {
  readonly auth = inject(AuthService); readonly usage = inject(UsageStreamService);
  private readonly api = inject(ApiService); readonly resource = signal<Resource | null>(null); readonly error = signal('');
  constructor() {
    const session = this.auth.session(); if (session && !this.usage.connected()) this.usage.connect(session);
    this.api.resources().subscribe({next: resources => {
      const resource = resources[0]; if (!resource) { this.error.set('No metered resource is configured.'); return; }
      this.resource.set(resource); this.api.currentUsage(resource.id).subscribe({next: value => this.usage.seed(value.total_units), error: () => this.error.set('Current usage is unavailable.')});
    }, error: () => this.error.set('Tenant resources are unavailable.')});
  }
}
