import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { ListResponse, Plan } from '../models';

/** GET /v1/plans — public. */
@Injectable({ providedIn: 'root' })
export class PlansService {
  private readonly http = inject(HttpClient);

  list(): Observable<Plan[]> {
    return this.http.get<ListResponse<Plan>>('/v1/plans').pipe(map((r) => r.items));
  }
}
