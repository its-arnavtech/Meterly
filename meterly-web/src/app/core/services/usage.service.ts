import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Timeseries, TimeseriesQuery, UsageCurrent } from '../models';

/** GET /v1/usage/current, GET /v1/usage/timeseries — any role. */
@Injectable({ providedIn: 'root' })
export class UsageService {
  private readonly http = inject(HttpClient);

  current(): Observable<UsageCurrent> {
    return this.http.get<UsageCurrent>('/v1/usage/current');
  }

  timeseries(query: TimeseriesQuery): Observable<Timeseries> {
    let params = new HttpParams().set('resource_id', query.resource_id);
    if (query.granularity) {
      params = params.set('granularity', query.granularity);
    }
    if (query.from) {
      params = params.set('from', query.from);
    }
    if (query.to) {
      params = params.set('to', query.to);
    }
    return this.http.get<Timeseries>('/v1/usage/timeseries', { params });
  }
}
