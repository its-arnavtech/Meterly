import { ChangeDetectionStrategy, Component } from '@angular/core';

@Component({
  selector: 'app-root',
  standalone: true,
  template: `
    <main class="shell">
      <p class="eyebrow">METERLY · PHASE 0</p>
      <h1>Usage you can trust.<br><span>Billing you can prove.</span></h1>
      <p class="lede">The POC workspace is ready. Feature branches add tenant isolation, live metering, exact invoices, and the customer dashboard.</p>
    </main>
  `,
  styles: [`
    :host { display: block; min-height: 100vh; }
    .shell { max-width: 880px; margin: 0 auto; padding: 15vh 32px; }
    .eyebrow { color: #0d9488; font-weight: 700; letter-spacing: .15em; }
    h1 { margin: 24px 0; font-size: clamp(3rem, 8vw, 6.8rem); line-height: .95; letter-spacing: -.055em; }
    h1 span { color: #0d9488; }
    .lede { max-width: 650px; color: #475569; font-size: 1.25rem; line-height: 1.7; }
  `],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class AppComponent {}

