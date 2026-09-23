import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from '../services/auth.service';
import { SnackbarService } from '../services/snackbar.service';

describe('authInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let authServiceSpy: jasmine.SpyObj<AuthService>;
  let snackbarSpy: jasmine.SpyObj<SnackbarService>;

  function configure(accessToken: string | null): void {
    authServiceSpy = jasmine.createSpyObj<AuthService>('AuthService', ['accessToken', 'refresh', 'logout']);
    authServiceSpy.accessToken.and.returnValue(accessToken);
    snackbarSpy = jasmine.createSpyObj<SnackbarService>('SnackbarService', ['showError', 'showInfo']);

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: authServiceSpy },
        { provide: SnackbarService, useValue: snackbarSpy },
        { provide: Router, useValue: jasmine.createSpyObj('Router', ['navigate']) }
      ]
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
  }

  afterEach(() => httpMock.verify());

  it('attaches the Authorization header for API requests when a token is present', () => {
    configure('token-123');

    http.get('/v1/me').subscribe();

    const req = httpMock.expectOne('/v1/me');
    expect(req.request.headers.get('Authorization')).toBe('Bearer token-123');
    req.flush({});
  });

  it('does not attach a header when there is no token', () => {
    configure(null);

    http.get('/v1/me').subscribe();

    const req = httpMock.expectOne('/v1/me');
    expect(req.request.headers.has('Authorization')).toBeFalse();
    req.flush({});
  });

  it('refreshes once and retries the original request on a 401', () => {
    configure('expired-token');
    authServiceSpy.refresh.and.returnValue(
      of({ access_token: 'fresh-token', token_type: 'Bearer' as const, expires_in: 900, user: { id: 'u1', email: 'a@b.com', role: 'TENANT_ADMIN' as const, tenant_id: 't1', tenant_name: 'Acme' } })
    );

    let result: unknown;
    http.get('/v1/usage/current').subscribe((res) => (result = res));

    const first = httpMock.expectOne('/v1/usage/current');
    expect(first.request.headers.get('Authorization')).toBe('Bearer expired-token');
    first.flush({ code: 'INVALID_TOKEN' }, { status: 401, statusText: 'Unauthorized' });

    expect(authServiceSpy.refresh).toHaveBeenCalledTimes(1);

    const retried = httpMock.expectOne('/v1/usage/current');
    expect(retried.request.headers.get('Authorization')).toBe('Bearer fresh-token');
    retried.flush({ ok: true });

    expect(result).toEqual({ ok: true });
    expect(authServiceSpy.logout).not.toHaveBeenCalled();
  });

  it('logs out and stops retrying when the refresh itself fails', () => {
    configure('expired-token');
    authServiceSpy.refresh.and.returnValue(throwError(() => new Error('refresh failed')));

    let errored = false;
    http.get('/v1/usage/current').subscribe({ error: () => (errored = true) });

    httpMock.expectOne('/v1/usage/current').flush({}, { status: 401, statusText: 'Unauthorized' });

    expect(authServiceSpy.logout).toHaveBeenCalledTimes(1);
    expect(errored).toBeTrue();
  });

  it('shows a global error snackbar for non-401 failures', () => {
    configure('token-123');

    http.get('/v1/usage/current').subscribe({ error: () => undefined });

    httpMock.expectOne('/v1/usage/current').flush({ detail: 'boom' }, { status: 500, statusText: 'Server Error' });

    expect(snackbarSpy.showError).toHaveBeenCalledTimes(1);
  });
});
