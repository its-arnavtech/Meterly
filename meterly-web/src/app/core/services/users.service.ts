import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpContext } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { CreateUserRequest, ListResponse, UpdateUserRequest, User } from '../models';

/** GET|POST /v1/users, PATCH /v1/users/{id} — admin only. */
@Injectable({ providedIn: 'root' })
export class UsersService {
  private readonly http = inject(HttpClient);

  list(): Observable<User[]> {
    return this.http.get<ListResponse<User>>('/v1/users').pipe(map((r) => r.items));
  }

  invite(req: CreateUserRequest, context?: HttpContext): Observable<User> {
    return this.http.post<User>('/v1/users', req, { context });
  }

  /** 422 LAST_ADMIN / CANNOT_MODIFY_SELF per docs/api-contract.md §1. */
  update(id: string, req: UpdateUserRequest): Observable<User> {
    return this.http.patch<User>(`/v1/users/${id}`, req);
  }
}
