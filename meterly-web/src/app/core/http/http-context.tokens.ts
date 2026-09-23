import { HttpContextToken } from '@angular/common/http';

/**
 * Set on a request to suppress the global error snackbar (see AuthInterceptor).
 * Used by forms (login, signup, ...) that render field/inline errors themselves
 * and would otherwise show a duplicate toast.
 */
export const SUPPRESS_GLOBAL_ERROR = new HttpContextToken<boolean>(() => false);
