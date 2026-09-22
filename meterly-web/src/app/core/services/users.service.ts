import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { CreateUserRequest, ListResponse, UpdateUserRequest, User } from '../models';

/** GET|POST /v1/users, PATCH /v1/users/{id} — admin only. */
@Injectable({ providedIn: 'root' })
export class UsersService {
  private readonly http = inject(HttpClient);

  list(): Observable<User[]> {
    return this.http.get<ListResponse<User>>('/v1/users').pipe(map((r) => r.items));
  }

  invite(req: CreateUserRequest): Observable<User> {
    return this.http.post<User>('/v1/users', req);
  }

  update(id: string, req: UpdateUserRequest): Observable<User> {
    return this.http.patch<User>(`/v1/users/${id}`, req);
  }
}
