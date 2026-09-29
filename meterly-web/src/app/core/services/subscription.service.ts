import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { SubscriptionState } from '../models';

/** GET|PUT /v1/subscription — admin only. */
@Injectable({ providedIn: 'root' })
export class SubscriptionService {
  private readonly http = inject(HttpClient);

  get(): Observable<SubscriptionState> {
    return this.http.get<SubscriptionState>('/v1/subscription');
  }

  /** Effective at the start of the next billing period; re-selecting the current plan cancels a pending change. */
  changePlan(planCode: string): Observable<SubscriptionState> {
    return this.http.put<SubscriptionState>('/v1/subscription', { plan_code: planCode });
  }
}
