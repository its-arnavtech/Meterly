import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { ListResponse, NotificationRecord } from '../models';

/** GET /v1/notifications — admin only. */
@Injectable({ providedIn: 'root' })
export class NotificationsService {
  private readonly http = inject(HttpClient);

  list(): Observable<NotificationRecord[]> {
    return this.http.get<ListResponse<NotificationRecord>>('/v1/notifications').pipe(map((r) => r.items));
  }
}
