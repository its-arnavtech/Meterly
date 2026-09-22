import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { BillingRunRequest, BillingRunResult, InvoiceDetail, InvoiceSummary, ListResponse } from '../models';

/** GET /v1/invoices[/{id}], GET /v1/billing/preview, POST /v1/billing/run — admin only. */
@Injectable({ providedIn: 'root' })
export class BillingService {
  private readonly http = inject(HttpClient);

  invoices(): Observable<InvoiceSummary[]> {
    return this.http.get<ListResponse<InvoiceSummary>>('/v1/invoices').pipe(map((r) => r.items));
  }

  invoice(id: string): Observable<InvoiceDetail> {
    return this.http.get<InvoiceDetail>(`/v1/invoices/${id}`);
  }

  preview(): Observable<InvoiceDetail> {
    return this.http.get<InvoiceDetail>('/v1/billing/preview');
  }

  /** 201 = generated now, 200 = already invoiced for that period (idempotent re-run). */
  run(req: BillingRunRequest = {}): Observable<BillingRunResult> {
    return this.http
      .post<InvoiceDetail>('/v1/billing/run', req, { observe: 'response' })
      .pipe(map((resp) => ({ created: resp.status === 201, invoice: resp.body as InvoiceDetail })));
  }
}
