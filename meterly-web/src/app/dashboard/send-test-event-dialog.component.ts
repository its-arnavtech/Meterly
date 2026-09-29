import { Component, inject, signal } from '@angular/core';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { UsageEventsService } from '../core/services/usage-events.service';
import { SnackbarService } from '../core/services/snackbar.service';
import { UsageItem } from '../core/models';

export interface SendTestEventDialogData {
  resources: UsageItem[];
}

/** POST /v1/usage-events with a fresh idempotency key — admin-only "send test event" action. */
@Component({
  selector: 'app-send-test-event-dialog',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatProgressSpinnerModule
  ],
  template: `
    <h2 mat-dialog-title>Send test event</h2>
    <form [formGroup]="form" (ngSubmit)="submit()">
      <mat-dialog-content>
        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Resource</mat-label>
          <mat-select formControlName="resource_id" required>
            @for (resource of data.resources; track resource.resource_id) {
              <mat-option [value]="resource.resource_id">{{ resource.resource_name }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Quantity</mat-label>
          <input matInput type="number" min="1" formControlName="quantity" required />
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close [disabled]="sending()">Cancel</button>
        <button mat-flat-button color="primary" type="submit" [disabled]="form.invalid || sending()">
          @if (sending()) {
            <mat-spinner diameter="18"></mat-spinner>
          } @else {
            Send
          }
        </button>
      </mat-dialog-actions>
    </form>
  `,
  styles: [`.full-width { width: 100%; }`]
})
export class SendTestEventDialogComponent {
  private readonly fb = inject(FormBuilder);
  private readonly usageEvents = inject(UsageEventsService);
  private readonly snackbar = inject(SnackbarService);
  private readonly ref = inject(MatDialogRef<SendTestEventDialogComponent>);
  readonly data = inject<SendTestEventDialogData>(MAT_DIALOG_DATA);

  readonly sending = signal(false);

  readonly form = this.fb.nonNullable.group({
    resource_id: [this.data.resources[0]?.resource_id ?? '', [Validators.required]],
    quantity: [1, [Validators.required, Validators.min(1)]]
  });

  submit(): void {
    if (this.form.invalid || this.sending()) {
      return;
    }
    this.sending.set(true);
    const { resource_id, quantity } = this.form.getRawValue();
    this.usageEvents
      .send({ resource_id, quantity, idempotency_key: crypto.randomUUID() })
      .subscribe({
        next: () => {
          this.snackbar.showInfo('Test event sent.');
          this.ref.close(true);
        },
        error: () => {
          this.sending.set(false);
        }
      });
  }
}
