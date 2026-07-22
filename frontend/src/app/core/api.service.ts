import {HttpClient} from '@angular/common/http';
import {Injectable} from '@angular/core';
import {Observable} from 'rxjs';
import {Invoice, Plan, Resource} from './models';

@Injectable({providedIn: 'root'})
export class ApiService {
  constructor(private readonly http: HttpClient) {}
  resources(): Observable<Resource[]> { return this.http.get<Resource[]>('http://127.0.0.1:8080/v1/resources'); }
  currentPlan(): Observable<Plan> { return this.http.get<Plan>('http://127.0.0.1:8080/v1/plans/current'); }
  currentUsage(resourceId: string): Observable<{total_units: number}> {
    return this.http.get<{total_units: number}>(`http://127.0.0.1:8082/v1/usage/current?resource_id=${resourceId}`);
  }
  listInvoices(): Observable<Invoice[]> { return this.http.get<Invoice[]>('http://127.0.0.1:8083/v1/invoices'); }
  getInvoice(id: string): Observable<Invoice> { return this.http.get<Invoice>(`http://127.0.0.1:8083/v1/invoices/${id}`); }
  runBilling(periodStart: string, periodEnd: string): Observable<Invoice> {
    return this.http.post<Invoice>('http://127.0.0.1:8083/v1/billing/run', {period_start: periodStart, period_end: periodEnd});
  }
}
