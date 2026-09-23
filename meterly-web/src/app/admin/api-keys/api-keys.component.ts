import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialog } from '@angular/material/dialog';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ApiKeysService } from '../../core/services/api-keys.service';
import { SnackbarService } from '../../core/services/snackbar.service';
import { ConfirmDialogComponent } from '../../shared/components/confirm-dialog.component';
import { ApiKey, CreatedApiKey } from '../../core/models';
import { CreateApiKeyDialogComponent } from './create-api-key-dialog.component';
import { RevealApiKeyDialogComponent } from './reveal-api-key-dialog.component';

@Component({
  selector: 'app-api-keys',
  standalone: true,
  imports: [DatePipe, MatTableModule, MatButtonModule, MatIconModule, MatTooltipModule],
  templateUrl: './api-keys.component.html',
  styleUrl: './api-keys.component.scss'
})
export class ApiKeysComponent implements OnInit {
  private readonly apiKeys = inject(ApiKeysService);
  private readonly dialog = inject(MatDialog);
  private readonly snackbar = inject(SnackbarService);

  readonly keys = signal<ApiKey[]>([]);
  readonly loading = signal(true);
  readonly columns = ['name', 'prefix', 'created_at', 'last_used_at', 'status', 'actions'];

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.apiKeys.list().subscribe({
      next: (items) => {
        this.keys.set(items);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  openCreate(): void {
    const ref = this.dialog.open<CreateApiKeyDialogComponent, unknown, CreatedApiKey | undefined>(
      CreateApiKeyDialogComponent,
      { width: '420px' }
    );
    ref.afterClosed().subscribe((created) => {
      if (!created) {
        return;
      }
      this.load();
      this.dialog.open(RevealApiKeyDialogComponent, { data: created, width: '480px', disableClose: true });
    });
  }

  revoke(key: ApiKey): void {
    const ref = this.dialog.open(ConfirmDialogComponent, {
      data: {
        title: 'Revoke API key',
        message: `Revoke "${key.name}"? Any client still using it will start getting 401 Unauthorized immediately.`,
        confirmLabel: 'Revoke',
        danger: true
      }
    });
    ref.afterClosed().subscribe((confirmed) => {
      if (!confirmed) {
        return;
      }
      this.apiKeys.revoke(key.id).subscribe({
        next: () => {
          this.snackbar.showInfo(`"${key.name}" revoked.`);
          this.load();
        }
      });
    });
  }
}
