import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Invoice } from './models';

const RESOURCE_ID = '55555555-5555-5555-5555-555555555555';

@Injectable({providedIn: 'root'})
export class ApiService {
  constructor(private readonly http: HttpClient) {}

  currentUsage(): Observable<{total_units: number}> {
    return this.http.get<{total_units: number}>(
      `http://127.0.0.1:8082/v1/usage/current?resource_id=${RESOURCE_ID}`);
  }

  emitUsage(apiKey: string): Observable<unknown> {
    return this.http.post('http://127.0.0.1:8181/v1/usage-events', {
      idempotency_key: crypto.randomUUID(),
      resource_id: RESOURCE_ID,
      quantity: 1
    }, {headers: new HttpHeaders({'X-Api-Key': apiKey})});
  }

  listInvoices(): Observable<Invoice[]> {
    return this.http.get<Invoice[]>('http://127.0.0.1:8082/v1/invoices');
  }

  runBilling(periodStart: string, periodEnd: string): Observable<Invoice> {
    return this.http.post<Invoice>('http://127.0.0.1:8082/v1/billing/run', {
      period_start: periodStart,
      period_end: periodEnd
    });
  }
}
