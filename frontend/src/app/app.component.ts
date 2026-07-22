import {ChangeDetectionStrategy, Component, inject} from '@angular/core';
import {MatButtonModule} from '@angular/material/button';
import {Router, RouterLink, RouterLinkActive, RouterOutlet} from '@angular/router';
import {AuthService} from './core/auth.service';
import {UsageStreamService} from './core/usage-stream.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, MatButtonModule],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class AppComponent {
  readonly auth = inject(AuthService);
  private readonly usage = inject(UsageStreamService);
  private readonly router = inject(Router);
  logout(): void {
    this.usage.disconnect();
    this.auth.logout();
    void this.router.navigate(['/login']);
  }
}
