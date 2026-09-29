import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { ApiKey, CreateApiKeyRequest, CreatedApiKey, ListResponse } from '../models';

/** GET|POST /v1/api-keys, DELETE /v1/api-keys/{id} — admin only. */
@Injectable({ providedIn: 'root' })
export class ApiKeysService {
  private readonly http = inject(HttpClient);

  list(): Observable<ApiKey[]> {
    return this.http.get<ListResponse<ApiKey>>('/v1/api-keys').pipe(map((r) => r.items));
  }

  /** The response carries the raw key exactly once — never fetchable again. */
  create(req: CreateApiKeyRequest): Observable<CreatedApiKey> {
    return this.http.post<CreatedApiKey>('/v1/api-keys', req);
  }

  revoke(id: string): Observable<void> {
    return this.http.delete<void>(`/v1/api-keys/${id}`);
  }
}
