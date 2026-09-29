import { Component, inject, signal } from '@angular/core';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ResourcesService } from '../../core/services/resources.service';
import { problemOf } from '../../shared/utils/problem-detail.util';
import { MeteredResource } from '../../core/models';

export interface ResourceDialogData {
  resource?: MeteredResource;
}

export type ResourceDialogResult = MeteredResource;

/** Create or rename a metered resource (POST/PATCH /v1/resources). */
@Component({
  selector: 'app-resource-dialog',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatProgressSpinnerModule
  ],
  template: `
    <h2 mat-dialog-title>{{ data.resource ? 'Rename resource' : 'New resource' }}</h2>
    <form [formGroup]="form" (ngSubmit)="submit()">
      <mat-dialog-content>
        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Name</mat-label>
          <input matInput formControlName="name" placeholder="Webhooks" required autofocus />
        </mat-form-field>
        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Unit label</mat-label>
          <input matInput formControlName="unit_label" placeholder="delivery" required />
        </mat-form-field>
        @if (errorMessage()) {
          <p class="error" role="alert">{{ errorMessage() }}</p>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close [disabled]="saving()">Cancel</button>
        <button mat-flat-button color="primary" type="submit" [disabled]="form.invalid || saving()">
          @if (saving()) {
            <mat-spinner diameter="18"></mat-spinner>
          } @else {
            Save
          }
        </button>
      </mat-dialog-actions>
    </form>
  `,
  styles: [`.full-width { width: 100%; } .error { color: var(--meterly-danger, #c62828); font-size: 0.85rem; }`]
})
export class ResourceDialogComponent {
  private readonly fb = inject(FormBuilder);
  private readonly resourcesService = inject(ResourcesService);
  private readonly ref = inject(MatDialogRef<ResourceDialogComponent, ResourceDialogResult>);
  readonly data = inject<ResourceDialogData>(MAT_DIALOG_DATA, { optional: true }) ?? {};

  readonly saving = signal(false);
  readonly errorMessage = signal<string | null>(null);

  readonly form = this.fb.nonNullable.group({
    name: [this.data.resource?.name ?? '', [Validators.required, Validators.maxLength(100)]],
    unit_label: [this.data.resource?.unit_label ?? '', [Validators.required, Validators.maxLength(40)]]
  });

  submit(): void {
    if (this.form.invalid || this.saving()) {
      return;
    }
    this.saving.set(true);
    const req = this.form.getRawValue();
    const request$ = this.data.resource
      ? this.resourcesService.update(this.data.resource.id, req)
      : this.resourcesService.create(req);
    request$.subscribe({
      next: (resource) => this.ref.close(resource),
      error: (err) => {
        this.saving.set(false);
        const problem = problemOf(err);
        this.errorMessage.set(
          problem?.code === 'RESOURCE_NAME_TAKEN' ? 'A resource with that name already exists.' : problem?.detail || 'Unable to save resource.'
        );
      }
    });
  }
}
