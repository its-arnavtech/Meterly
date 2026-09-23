import { Component, Inject, signal } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { CreatedApiKey } from '../../core/models';

/**
 * Shows a freshly created API key exactly once (docs/api-contract.md §1: "the only time the
 * raw key is returned"). The key is never fetchable again after this dialog closes.
 */
@Component({
  selector: 'app-reveal-api-key-dialog',
  standalone: true,
  imports: [MatDialogModule, MatButtonModule, MatIconModule],
  template: `
    <h2 mat-dialog-title>Copy your API key now</h2>
    <mat-dialog-content>
      <p class="warning">
        <mat-icon>warning</mat-icon>
        This is the only time the full key is shown. Store it somewhere safe — Meterly cannot
        display it again.
      </p>
      <div class="key-box">
        <code>{{ data.key }}</code>
        <button mat-icon-button type="button" [attr.aria-label]="'Copy API key'" (click)="copy()">
          <mat-icon>{{ copied() ? 'check' : 'content_copy' }}</mat-icon>
        </button>
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-flat-button color="primary" mat-dialog-close>Done</button>
    </mat-dialog-actions>
  `,
  styles: [
    `
      .warning {
        display: flex;
        gap: 8px;
        align-items: flex-start;
        color: var(--meterly-danger, #c62828);
        font-size: 0.9rem;
      }
      .key-box {
        display: flex;
        align-items: center;
        gap: 8px;
        background: #f4f6f8;
        border-radius: 4px;
        padding: 8px 12px;
        margin-top: 12px;
        word-break: break-all;
      }
    `
  ]
})
export class RevealApiKeyDialogComponent {
  readonly copied = signal(false);

  constructor(@Inject(MAT_DIALOG_DATA) public data: CreatedApiKey) {}

  copy(): void {
    navigator.clipboard
      ?.writeText(this.data.key)
      .then(() => {
        this.copied.set(true);
        setTimeout(() => this.copied.set(false), 2000);
      })
      .catch(() => undefined);
  }
}
