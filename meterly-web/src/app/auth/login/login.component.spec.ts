import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { LoginComponent } from './login.component';
import { AuthService } from '../../core/services/auth.service';

describe('LoginComponent', () => {
  let authServiceSpy: jasmine.SpyObj<AuthService>;
  let routerSpy: jasmine.Spy;

  beforeEach(async () => {
    authServiceSpy = jasmine.createSpyObj<AuthService>('AuthService', ['login']);

    await TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [
        provideRouter([]),
        provideNoopAnimations(),
        { provide: AuthService, useValue: authServiceSpy },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({}) } }
        }
      ]
    }).compileComponents();
    routerSpy = spyOn(TestBed.inject(Router), 'navigateByUrl');
  });

  function createComponent() {
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('navigates to the dashboard on successful login', () => {
    authServiceSpy.login.and.returnValue(
      of({
        access_token: 't',
        token_type: 'Bearer' as const,
        expires_in: 900,
        user: { id: 'u1', email: 'admin@acme.test', role: 'TENANT_ADMIN' as const, tenant_id: 't1', tenant_name: 'Acme' }
      })
    );
    const fixture = createComponent();
    const component = fixture.componentInstance;

    component.form.setValue({ email: 'admin@acme.test', password: 'Meterly!2026' });
    component.submit();

    expect(routerSpy).toHaveBeenCalledWith('/dashboard');
    expect(component.errorMessage()).toBeNull();
  });

  it('shows a friendly message for invalid credentials without enumerating the account', () => {
    const error = new HttpErrorResponse({
      status: 401,
      error: { code: 'INVALID_CREDENTIALS', detail: 'Invalid email or password.' }
    });
    authServiceSpy.login.and.returnValue(throwError(() => error));
    const fixture = createComponent();
    const component = fixture.componentInstance;

    component.form.setValue({ email: 'admin@acme.test', password: 'wrong-password' });
    component.submit();
    fixture.detectChanges();

    expect(component.errorMessage()).toBe('Incorrect email or password.');
    expect(component.loading()).toBeFalse();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('[role="alert"]')?.textContent).toContain('Incorrect email or password.');
  });

  it('surfaces the Retry-After detail for a throttled login', () => {
    const error = new HttpErrorResponse({
      status: 429,
      error: { code: 'TOO_MANY_ATTEMPTS', detail: 'Too many attempts, retry in 30s.' }
    });
    authServiceSpy.login.and.returnValue(throwError(() => error));
    const fixture = createComponent();
    const component = fixture.componentInstance;

    component.form.setValue({ email: 'admin@acme.test', password: 'Meterly!2026' });
    component.submit();

    expect(component.errorMessage()).toBe('Too many attempts, retry in 30s.');
  });

  it('does not submit an invalid form', () => {
    const fixture = createComponent();
    const component = fixture.componentInstance;

    component.submit();

    expect(authServiceSpy.login).not.toHaveBeenCalled();
    expect(component.form.controls.email.touched).toBeTrue();
  });
});
