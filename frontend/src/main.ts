import {importProvidersFrom, provideZoneChangeDetection} from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { AppComponent } from './app/app.component';
import {authInterceptor} from './app/core/auth.interceptor';
import {provideRouter} from '@angular/router';
import {routes} from './app/app.routes';
import {CoreModule} from './app/core/core.module';

bootstrapApplication(AppComponent, {
  providers: [
    provideZoneChangeDetection(),
    provideAnimationsAsync(),
    provideHttpClient(withInterceptors([authInterceptor])),
    provideRouter(routes),
    importProvidersFrom(CoreModule)
  ]
}).catch((error: unknown) => console.error(error));
