import { Component, OnInit, inject, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { HttpContext } from '@angular/common/http';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatRadioModule } from '@angular/material/radio';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AuthService } from '../../core/services/auth.service';
import { PlansService } from '../../core/services/plans.service';
import { SUPPRESS_GLOBAL_ERROR } from '../../core/http/http-context.tokens';
import { fieldErrorsOf, problemOf } from '../../shared/utils/problem-detail.util';
import { MoneyPipe } from '../../shared/pipes/money.pipe';
import { Plan } from '../../core/models';

@Component({
  selector: 'app-signup',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatCardModule,
    MatRadioModule,
    MatProgressSpinnerModule,
    MoneyPipe,
    DecimalPipe
  ],
  templateUrl: './signup.component.html',
  styleUrl: './signup.component.scss'
})
export class SignupComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly auth = inject(AuthService);
  private readonly plansService = inject(PlansService);
  private readonly router = inject(Router);

  readonly loading = signal(false);
  readonly errorMessage = signal<string | null>(null);
  readonly plans = signal<Plan[]>([]);

  readonly form = this.fb.nonNullable.group({
    tenant_name: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(100)]],
    admin_email: ['', [Validators.required, Validators.email, Validators.maxLength(254)]],
    admin_password: ['', [Validators.required, Validators.minLength(10), Validators.maxLength(72)]],
    plan_code: ['STARTER', [Validators.required]]
  });

  fieldErrors: Record<string, string> = {};

  ngOnInit(): void {
    this.plansService.list().subscribe({
      next: (plans) => this.plans.set(plans),
      error: () => this.plans.set([])
    });
  }

  submit(): void {
    if (this.form.invalid || this.loading()) {
      this.form.markAllAsTouched();
      return;
    }
    this.loading.set(true);
    this.errorMessage.set(null);
    this.fieldErrors = {};
    const req = this.form.getRawValue();
    const context = new HttpContext().set(SUPPRESS_GLOBAL_ERROR, true);

    this.auth.signup(req, context).subscribe({
      next: () => {
        this.auth.login(req.admin_email, req.admin_password, context).subscribe({
          next: () => this.router.navigateByUrl('/dashboard'),
          error: () => {
            // Tenant was created but the follow-up login failed; send them to sign in manually.
            this.loading.set(false);
            this.router.navigate(['/login']);
          }
        });
      },
      error: (err) => {
        this.loading.set(false);
        const problem = problemOf(err);
        this.fieldErrors = fieldErrorsOf(problem);
        if (problem?.code === 'EMAIL_TAKEN') {
          this.errorMessage.set('That email is already registered. Try signing in instead.');
        } else if (problem?.code === 'UNKNOWN_PLAN') {
          this.errorMessage.set('Selected plan is no longer available. Pick another plan.');
        } else if (problem?.code === 'VALIDATION_FAILED') {
          this.errorMessage.set('Please fix the highlighted fields.');
        } else {
          this.errorMessage.set(problem?.detail || 'Unable to create your account right now.');
        }
      }
    });
  }
}
