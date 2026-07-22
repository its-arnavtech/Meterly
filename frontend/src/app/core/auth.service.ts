import {HttpClient} from '@angular/common/http';
import {Injectable, signal} from '@angular/core';
import {Observable, tap} from 'rxjs';
import {Session} from './models';

@Injectable({providedIn: 'root'})
export class AuthService {
  readonly session = signal<Session | null>(null);
  constructor(private readonly http: HttpClient) {}
  login(email: string, password: string): Observable<Session> {
    return this.http.post<Session>('http://127.0.0.1:8080/v1/auth/login', {email, password})
      .pipe(tap(session => this.session.set(session)));
  }
  logout(): void { this.session.set(null); }
}
