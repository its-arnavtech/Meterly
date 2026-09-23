import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { CreateResourceRequest, ListResponse, MeteredResource, UpdateResourceRequest } from '../models';

/** GET|POST /v1/resources, PATCH /v1/resources/{id}. */
@Injectable({ providedIn: 'root' })
export class ResourcesService {
  private readonly http = inject(HttpClient);

  list(): Observable<MeteredResource[]> {
    return this.http.get<ListResponse<MeteredResource>>('/v1/resources').pipe(map((r) => r.items));
  }

  create(req: CreateResourceRequest): Observable<MeteredResource> {
    return this.http.post<MeteredResource>('/v1/resources', req);
  }

  update(id: string, req: UpdateResourceRequest): Observable<MeteredResource> {
    return this.http.patch<MeteredResource>(`/v1/resources/${id}`, req);
  }
}
