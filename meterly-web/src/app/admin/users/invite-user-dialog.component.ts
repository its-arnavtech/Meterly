import { Component, inject, signal } from '@angular/core';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { HttpContext } from '@angular/common/http';
import { UsersService } from '../../core/services/users.service';
import { SUPPRESS_GLOBAL_ERROR } from '../../core/http/http-context.tokens';
import { fieldErrorsOf, problemOf } from '../../shared/utils/problem-detail.util';
import { Role, User } from '../../core/models';

@Component({
  selector: 'app-invite-user-dialog',
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
    <h2 mat-dialog-title>Invite user</h2>
    <form [formGroup]="form" (ngSubmit)="submit()">
      <mat-dialog-content>
        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Email</mat-label>
          <input matInput type="email" formControlName="email" required />
          @if (fieldErrors['email']) {
            <mat-error>{{ fieldErrors['email'] }}</mat-error>
          }
        </mat-form-field>
        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Temporary password</mat-label>
          <input matInput type="password" formControlName="password" required />
        </mat-form-field>
        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Role</mat-label>
          <mat-select formControlName="role">
            <mat-option value="TENANT_ADMIN">Admin</mat-option>
            <mat-option value="TENANT_USER">User (dashboard only)</mat-option>
          </mat-select>
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
            Invite
          }
        </button>
      </mat-dialog-actions>
    </form>
  `,
  styles: [`.full-width { width: 100%; } .error { color: var(--meterly-danger, #c62828); font-size: 0.85rem; }`]
})
export class InviteUserDialogComponent {
  private readonly fb = inject(FormBuilder);
  private readonly usersService = inject(UsersService);
  private readonly ref = inject(MatDialogRef<InviteUserDialogComponent, User>);

  readonly saving = signal(false);
  readonly errorMessage = signal<string | null>(null);
  fieldErrors: Record<string, string> = {};

  readonly form = this.fb.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(10), Validators.maxLength(72)]],
    role: this.fb.nonNullable.control<Role>('TENANT_USER', Validators.required)
  });

  submit(): void {
    if (this.form.invalid || this.saving()) {
      return;
    }
    this.saving.set(true);
    this.errorMessage.set(null);
    const context = new HttpContext().set(SUPPRESS_GLOBAL_ERROR, true);
    this.usersService.invite(this.form.getRawValue(), context).subscribe({
      next: (user) => this.ref.close(user),
      error: (err) => {
        this.saving.set(false);
        const problem = problemOf(err);
        this.fieldErrors = fieldErrorsOf(problem);
        this.errorMessage.set(
          problem?.code === 'EMAIL_TAKEN' ? 'That email is already in use.' : problem?.detail || 'Unable to invite user.'
        );
      }
    });
  }
}
