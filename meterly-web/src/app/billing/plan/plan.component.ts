import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { SubscriptionService } from '../../core/services/subscription.service';
import { PlansService } from '../../core/services/plans.service';
import { SnackbarService } from '../../core/services/snackbar.service';
import { MoneyPipe } from '../../shared/pipes/money.pipe';
import { Plan, SubscriptionState } from '../../core/models';

@Component({
  selector: 'app-plan',
  standalone: true,
  imports: [DatePipe, DecimalPipe, MatCardModule, MatButtonModule, MatIconModule, MatChipsModule, MoneyPipe],
  templateUrl: './plan.component.html',
  styleUrl: './plan.component.scss'
})
export class PlanComponent implements OnInit {
  private readonly subscriptionService = inject(SubscriptionService);
  private readonly plansService = inject(PlansService);
  private readonly snackbar = inject(SnackbarService);

  readonly loading = signal(true);
  readonly subscription = signal<SubscriptionState | null>(null);
  readonly plans = signal<Plan[]>([]);
  readonly changing = signal<string | null>(null);

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.plansService.list().subscribe({
      next: (plans) => this.plans.set(plans),
      error: () => this.plans.set([])
    });
    this.subscriptionService.get().subscribe({
      next: (sub) => {
        this.subscription.set(sub);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  changePlan(planCode: string): void {
    if (this.changing()) {
      return;
    }
    this.changing.set(planCode);
    this.subscriptionService.changePlan(planCode).subscribe({
      next: (sub) => {
        this.subscription.set(sub);
        this.changing.set(null);
        this.snackbar.showInfo(
          sub.scheduled ? `Plan change to ${sub.scheduled.plan.name} scheduled.` : 'Plan change cancelled.'
        );
      },
      error: () => this.changing.set(null)
    });
  }

  cancelScheduledChange(): void {
    const current = this.subscription()?.current;
    if (current) {
      this.changePlan(current.plan.code);
    }
  }

  isCurrentPlan(code: string): boolean {
    return this.subscription()?.current?.plan.code === code;
  }
}
