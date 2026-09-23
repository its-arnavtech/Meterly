import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { UsageEventRequest, UsageEventResponse } from '../models';

/** POST /v1/usage-events — used by the admin "send test event" action. */
@Injectable({ providedIn: 'root' })
export class UsageEventsService {
  private readonly http = inject(HttpClient);

  send(req: UsageEventRequest): Observable<UsageEventResponse> {
    return this.http.post<UsageEventResponse>('/v1/usage-events', req);
  }
}
