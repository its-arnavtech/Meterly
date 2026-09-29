import { APP_INITIALIZER, Provider } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AuthService } from '../services/auth.service';

/**
 * Attempts a silent refresh (POST /v1/auth/refresh, httpOnly cookie) before the app
 * renders anything gated by auth, so a page reload doesn't flash the login screen for
 * an already-signed-in user. Never blocks startup on failure — an absent/expired cookie
 * simply leaves the user signed out.
 */
export function provideAuthInitializer(): Provider {
  return {
    provide: APP_INITIALIZER,
    multi: true,
    useFactory: (auth: AuthService) => () =>
      firstValueFrom(auth.silentRefresh()).finally(() => auth.authReady.set(true)),
    deps: [AuthService]
  };
}
