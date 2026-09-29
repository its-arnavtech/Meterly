import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MatTabsModule } from '@angular/material/tabs';

@Component({
  selector: 'app-billing-shell',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, RouterOutlet, MatTabsModule],
  template: `
    <nav mat-tab-nav-bar [tabPanel]="panel" class="section-nav">
      <a mat-tab-link routerLink="invoices" routerLinkActive #invoices="routerLinkActive" [active]="invoices.isActive">
        Invoices
      </a>
      <a mat-tab-link routerLink="plan" routerLinkActive #plan="routerLinkActive" [active]="plan.isActive">
        Plan &amp; tiers
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
export class BillingShellComponent {}
