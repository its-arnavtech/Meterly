import { Injectable, inject } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ProblemDetail } from '../models';

/** Surfaces RFC 7807 problem+json errors (docs/api-contract.md §0) as snackbars. */
@Injectable({ providedIn: 'root' })
export class SnackbarService {
  private readonly snackBar = inject(MatSnackBar);

  showError(error: HttpErrorResponse | ProblemDetail | string): void {
    this.snackBar.open(this.toMessage(error), 'Dismiss', {
      duration: 7000,
      panelClass: ['meterly-snackbar-error']
    });
  }

  showInfo(message: string): void {
    this.snackBar.open(message, undefined, { duration: 4000, panelClass: ['meterly-snackbar-info'] });
  }

  private toMessage(error: HttpErrorResponse | ProblemDetail | string): string {
    if (typeof error === 'string') {
      return error;
    }
    const problem: ProblemDetail | undefined =
      error instanceof HttpErrorResponse ? (error.error as ProblemDetail) : error;
    if (problem?.detail) {
      return problem.detail;
    }
    if (problem?.title) {
      return problem.title;
    }
    if (error instanceof HttpErrorResponse) {
      if (error.status === 0) {
        return 'Network error — check your connection and try again.';
      }
      return `Request failed (${error.status}).`;
    }
    return 'Something went wrong.';
  }
}
