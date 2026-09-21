import { Instant, IsoDate, Uuid } from './common.models';

/** GET /v1/usage/current */
export interface UsageCurrent {
  period_start: IsoDate;
  /** Exclusive: the period is [period_start, period_end). */
  period_end: IsoDate;
  plan: UsagePlanSummary;
  items: UsageItem[];
}

export interface UsagePlanSummary {
  code: string;
  name: string;
  included_units: number;
}

export interface UsageItem {
  resource_id: Uuid;
  resource_name: string;
  unit_label: string;
  total_units: number;
  included_units: number;
  percent_of_included: number;
}

export type Granularity = 'hour' | 'day';

export interface TimeseriesQuery {
  resource_id: Uuid;
  granularity?: Granularity;
  from?: string;
  to?: string;
}

/** GET /v1/usage/timeseries */
export interface Timeseries {
  resource_id: Uuid;
  granularity: Granularity;
  from: string;
  to: string;
  points: TimeseriesPoint[];
}

export interface TimeseriesPoint {
  bucket_start: Instant;
  units: number;
}

/** STOMP MESSAGE body on /topic/tenants/{tenant_id}/usage. */
export interface UsageUpdate {
  tenant_id: Uuid;
  resource_id: Uuid;
  period_start: IsoDate;
  /** Authoritative running total for the period (monotonic within a period). */
  total_units: number;
  delta: number;
  occurred_at: Instant;
  recorded_at: Instant;
}

// ---------------------------------------------------------------- ingestion

/** POST /v1/usage-events */
export interface UsageEventRequest {
  resource_id: Uuid;
  /** 1..255 chars of [A-Za-z0-9._:-]. */
  idempotency_key: string;
  quantity: number;
  occurred_at?: Instant;
}

/** 202 Accepted, or 200 `{ "status": "duplicate" }` for an idempotent replay. */
export interface UsageEventResponse {
  status: 'accepted' | 'duplicate';
  idempotency_key?: string;
  received_at?: Instant;
}
