/**
 * Shared wire-level conventions from docs/api-contract.md §0.
 *
 * - JSON fields are snake_case, so these interfaces are too (no mapping layer).
 * - Money is always a decimal *string* ("274.00", "0.002000"); see {@link Money}.
 * - Timestamps are ISO-8601 UTC instants; dates are ISO dates ("2026-08-01").
 */

/** Plain-decimal money string, e.g. "278.69" or "0.002000". Never parse to a float for arithmetic. */
export type Money = string;

/** ISO-8601 UTC instant, e.g. "2026-09-21T17:04:05Z". */
export type Instant = string;

/** ISO calendar date, e.g. "2026-09-01". */
export type IsoDate = string;

/** UUID string. */
export type Uuid = string;

/** Every list endpoint wraps its results: `{ "items": [...] }`. */
export interface ListResponse<T> {
  items: T[];
}

/** RFC 7807 problem+json body with the Meterly `code` extension. */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  /** Stable UPPER_SNAKE identifier, e.g. `INVALID_CREDENTIALS`. */
  code?: string;
  request_id?: string;
  errors?: FieldError[];
}

export interface FieldError {
  field: string;
  message: string;
}
