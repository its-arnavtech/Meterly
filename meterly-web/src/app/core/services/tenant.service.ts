import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { MeResponse, Tenant } from '../models';

/** GET /v1/me, GET|PATCH /v1/tenant. */
@Injectable({ providedIn: 'root' })
export class TenantService {
  private readonly http = inject(HttpClient);

  me(): Observable<MeResponse> {
    return this.http.get<MeResponse>('/v1/me');
  }

  getTenant(): Observable<Tenant> {
    return this.http.get<Tenant>('/v1/tenant');
  }

  renameTenant(name: string): Observable<Tenant> {
    return this.http.patch<Tenant>('/v1/tenant', { name });
  }
}
