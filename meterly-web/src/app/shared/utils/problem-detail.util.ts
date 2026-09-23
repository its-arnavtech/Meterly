import { HttpErrorResponse } from '@angular/common/http';
import { ProblemDetail } from '../../core/models';

/** Extracts the RFC 7807 problem body from a failed HttpClient request, if present. */
export function problemOf(error: unknown): ProblemDetail | null {
  if (error instanceof HttpErrorResponse && error.error && typeof error.error === 'object') {
    return error.error as ProblemDetail;
  }
  return null;
}

/** Maps `errors: [{field, message}]` to a `{field: message}` lookup for template binding. */
export function fieldErrorsOf(problem: ProblemDetail | null): Record<string, string> {
  const out: Record<string, string> = {};
  for (const e of problem?.errors ?? []) {
    out[e.field] = e.message;
  }
  return out;
}
