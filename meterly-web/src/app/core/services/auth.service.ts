import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient, HttpContext } from '@angular/common/http';
import { Observable, catchError, finalize, map, of, shareReplay, tap, throwError } from 'rxjs';
import { AuthResponse, AuthUser, Role, SignupRequest, SignupResponse } from '../models';

/**
 * Access token lives in memory only (a signal) — never localStorage/sessionStorage,
 * per docs/Meterly_PDD.txt §8/§9 (reduce XSS token-theft surface). The refresh token
 * is an httpOnly cookie the browser manages; we never see its value.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);

  private readonly _accessToken = signal<string | null>(null);
  private readonly _currentUser = signal<AuthUser | null>(null);
  /** Set once the startup silent-refresh attempt (APP_INITIALIZER) has resolved. */
  readonly authReady = signal(false);

  readonly accessToken = this._accessToken.asReadonly();
  readonly currentUser = this._currentUser.asReadonly();
  readonly isAuthenticated = computed(() => this._accessToken() !== null && this._currentUser() !== null);
  readonly role = computed<Role | null>(() => this._currentUser()?.role ?? null);

  private expiresAt = 0;
  private refreshTimer: ReturnType<typeof setTimeout> | null = null;
  private refreshInFlight: Observable<AuthResponse> | null = null;

  login(email: string, password: string, context?: HttpContext): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>('/v1/auth/login', { email, password }, { withCredentials: true, context })
      .pipe(tap((res) => this.setSession(res)));
  }

  signup(req: SignupRequest, context?: HttpContext): Observable<SignupResponse> {
    return this.http.post<SignupResponse>('/v1/tenants', req, { context });
  }

  /** Rotates the refresh cookie and issues a new access token. Coalesces concurrent callers. */
  refresh(): Observable<AuthResponse> {
    if (this.refreshInFlight) {
      return this.refreshInFlight;
    }
    const req$ = this.http.post<AuthResponse>('/v1/auth/refresh', {}, { withCredentials: true }).pipe(
      tap((res) => this.setSession(res)),
      catchError((err) => {
        this.clearSession();
        return throwError(() => err);
      }),
      finalize(() => {
        this.refreshInFlight = null;
      }),
      shareReplay(1)
    );
    this.refreshInFlight = req$;
    return req$;
  }

  /** Used by APP_INITIALIZER: never throws, resolves true/false. */
  silentRefresh(): Observable<boolean> {
    return this.refresh().pipe(
      map(() => true),
      catchError(() => of(false))
    );
  }

  /** Ensures a non-expiring-soon access token, refreshing first if needed. Used before STOMP connects. */
  ensureFreshToken(): Observable<string | null> {
    if (this._accessToken() && Date.now() < this.expiresAt - 30_000) {
      return of(this._accessToken());
    }
    return this.refresh().pipe(
      map((res) => res.access_token),
      catchError(() => of(null))
    );
  }

  logout(): void {
    this.http.post('/v1/auth/logout', {}, { withCredentials: true }).subscribe({ error: () => undefined });
    this.clearSession();
  }

  private setSession(res: AuthResponse): void {
    this._accessToken.set(res.access_token);
    this._currentUser.set(res.user);
    this.expiresAt = Date.now() + res.expires_in * 1000;
    this.scheduleRefresh(res.expires_in);
  }

  private clearSession(): void {
    this._accessToken.set(null);
    this._currentUser.set(null);
    this.expiresAt = 0;
    if (this.refreshTimer) {
      clearTimeout(this.refreshTimer);
      this.refreshTimer = null;
    }
  }

  private scheduleRefresh(expiresInSeconds: number): void {
    if (this.refreshTimer) {
      clearTimeout(this.refreshTimer);
    }
    // Refresh at 90% of the access-token TTL (15 min TTL -> ~13.5 min), well before expiry.
    const delayMs = Math.max(5_000, expiresInSeconds * 1000 * 0.9);
    this.refreshTimer = setTimeout(() => {
      this.refresh().subscribe({ error: () => undefined });
    }, delayMs);
  }
}
