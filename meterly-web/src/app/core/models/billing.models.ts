import { Instant, IsoDate, Money, Uuid } from './common.models';

export type InvoiceStatus = 'ISSUED' | 'PREVIEW' | (string & {});

/** Row of GET /v1/invoices. */
export interface InvoiceSummary {
  id: Uuid;
  invoice_number: string;
  period_start: IsoDate;
  period_end: IsoDate;
  status: InvoiceStatus;
  currency: string;
  total_amount: Money;
  issued_at: Instant;
}

export interface InvoiceLineItem {
  line_number: number;
  description: string;
  /** `null` for the plan base-fee line. */
  resource_id: Uuid | null;
  quantity: number;
  unit_price: Money;
  amount: Money;
}

/**
 * GET /v1/invoices/{id} and POST /v1/billing/run.
 * GET /v1/billing/preview returns the same shape with `id`/`invoice_number`/`issued_at` null
 * and `status: "PREVIEW"`.
 */
export interface InvoiceDetail {
  id: Uuid | null;
  invoice_number: string | null;
  period_start: IsoDate;
  period_end: IsoDate;
  status: InvoiceStatus;
  currency: string;
  total_amount: Money;
  issued_at: Instant | null;
  plan: { code: string; name: string };
  line_items: InvoiceLineItem[];
}

export interface BillingRunRequest {
  /** 1st of a closed month; omitted = previous month (server default). */
  period_start?: IsoDate;
}

/** POST /v1/billing/run outcome: 201 = generated now, 200 = already invoiced (idempotent re-run). */
export interface BillingRunResult {
  created: boolean;
  invoice: InvoiceDetail;
}
