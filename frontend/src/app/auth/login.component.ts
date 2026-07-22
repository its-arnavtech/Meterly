import {ChangeDetectionStrategy, Component, inject, signal} from '@angular/core';
import {ReactiveFormsModule, FormBuilder, Validators} from '@angular/forms';
import {MatButtonModule} from '@angular/material/button';
import {MatCardModule} from '@angular/material/card';
import {MatFormFieldModule} from '@angular/material/form-field';
import {MatInputModule} from '@angular/material/input';
import {MatProgressSpinnerModule} from '@angular/material/progress-spinner';
import {Router} from '@angular/router';
import {AuthService} from '../core/auth.service';
import {UsageStreamService} from '../core/usage-stream.service';

@Component({
  selector: 'app-login', standalone: true,
  imports: [ReactiveFormsModule, MatButtonModule, MatCardModule, MatFormFieldModule, MatInputModule, MatProgressSpinnerModule],
  template: `
    <main class="login-shell">
      <section class="brand-panel"><div class="brand-mark">M</div><p class="eyebrow">METERLY / CORE MVP</p>
        <h1>Usage you can trust.<br><span>Billing you can prove.</span></h1>
        <p class="lede">Tenant-isolated metering, live usage, and auditable tiered invoices.</p>
      </section>
      <mat-card class="login-card" appearance="outlined"><mat-card-header><mat-card-title>Welcome back</mat-card-title>
        <mat-card-subtitle>Sign in with your tenant account</mat-card-subtitle></mat-card-header><mat-card-content>
        <form [formGroup]="form" (ngSubmit)="login()">
          <mat-form-field appearance="outline"><mat-label>Email</mat-label><input matInput formControlName="email" type="email"></mat-form-field>
          <mat-form-field appearance="outline"><mat-label>Password</mat-label><input matInput formControlName="password" type="password"></mat-form-field>
          @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
          <button mat-flat-button type="submit" [disabled]="busy() || form.invalid">@if (busy()) { <mat-spinner diameter="20" /> } @else { Enter workspace }</button>
        </form>
      </mat-card-content></mat-card>
    </main>`,
  styles: [`
    .login-shell{min-height:100vh;display:grid;grid-template-columns:minmax(0,1.2fr) minmax(360px,.8fr);align-items:center;gap:7vw;max-width:1280px;margin:auto;padding:7vw}.brand-mark{display:grid;place-items:center;width:42px;height:42px;border-radius:12px;color:#fff;background:#0f766e;font-weight:900}.eyebrow{color:#0f766e;font-size:.72rem;font-weight:800;letter-spacing:.16em}.brand-panel h1{margin:28px 0;font-size:clamp(3.2rem,6.8vw,6.3rem);line-height:.92;letter-spacing:-.06em}.brand-panel h1 span{color:#0f766e}.lede{max-width:690px;color:#475569;font-size:1.15rem;line-height:1.75}.login-card{padding:28px 20px;border-radius:24px;background:#ffffffd9}.login-card form{display:grid;gap:10px;margin-top:28px}mat-form-field,button{width:100%}button{height:48px}.error{color:#b91c1c}@media(max-width:900px){.login-shell{grid-template-columns:1fr}}
  `], changeDetection: ChangeDetectionStrategy.OnPush
})
export class LoginComponent {
  private readonly fb = inject(FormBuilder); private readonly auth = inject(AuthService);
  private readonly usage = inject(UsageStreamService); private readonly router = inject(Router);
  readonly busy = signal(false); readonly error = signal('');
  readonly form = this.fb.nonNullable.group({email: ['admin@meterly.local', [Validators.required, Validators.email]], password: ['meterly-demo', Validators.required]});
  login(): void {
    if (this.form.invalid) return; this.busy.set(true); this.error.set('');
    this.auth.login(this.form.controls.email.value, this.form.controls.password.value).subscribe({
      next: session => { this.usage.connect(session); void this.router.navigate(['/dashboard']); },
      error: () => { this.busy.set(false); this.error.set('Those credentials were not accepted.'); }
    });
  }
}
