import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MatTabsModule } from '@angular/material/tabs';

@Component({
  selector: 'app-admin-shell',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, RouterOutlet, MatTabsModule],
  template: `
    <nav mat-tab-nav-bar [tabPanel]="panel" class="section-nav">
      <a mat-tab-link routerLink="api-keys" routerLinkActive #k="routerLinkActive" [active]="k.isActive">
        API keys
      </a>
      <a mat-tab-link routerLink="users" routerLinkActive #u="routerLinkActive" [active]="u.isActive">Users</a>
      <a mat-tab-link routerLink="resources" routerLinkActive #r="routerLinkActive" [active]="r.isActive">
        Resources
      </a>
      <a mat-tab-link routerLink="notifications" routerLinkActive #n="routerLinkActive" [active]="n.isActive">
        Notifications
      </a>
    </nav>
    <mat-tab-nav-panel #panel>
      <router-outlet />
    </mat-tab-nav-panel>
  `,
  styles: [
    `
      .section-nav {
        border-bottom: 1px solid var(--meterly-border);
        background: #fff;
      }
    `
  ]
})
export class AdminShellComponent {}
