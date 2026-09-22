import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialog } from '@angular/material/dialog';
import { MatChipsModule } from '@angular/material/chips';
import { ResourcesService } from '../../core/services/resources.service';
import { SnackbarService } from '../../core/services/snackbar.service';
import { ConfirmDialogComponent } from '../../shared/components/confirm-dialog.component';
import { MeteredResource } from '../../core/models';
import { ResourceDialogComponent, ResourceDialogResult } from './resource-dialog.component';

@Component({
  selector: 'app-resources',
  standalone: true,
  imports: [DatePipe, MatTableModule, MatButtonModule, MatIconModule, MatChipsModule],
  templateUrl: './resources.component.html',
  styleUrl: './resources.component.scss'
})
export class ResourcesComponent implements OnInit {
  private readonly resourcesService = inject(ResourcesService);
  private readonly dialog = inject(MatDialog);
  private readonly snackbar = inject(SnackbarService);

  readonly resources = signal<MeteredResource[]>([]);
  readonly loading = signal(true);
  readonly columns = ['name', 'unit_label', 'status', 'created_at', 'actions'];

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.resourcesService.list().subscribe({
      next: (items) => {
        this.resources.set(items);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  openCreate(): void {
    const ref = this.dialog.open<ResourceDialogComponent, unknown, ResourceDialogResult>(ResourceDialogComponent, {
      width: '420px'
    });
    ref.afterClosed().subscribe((result) => {
      if (result) {
        this.load();
      }
    });
  }

  rename(resource: MeteredResource): void {
    const ref = this.dialog.open<ResourceDialogComponent, unknown, ResourceDialogResult>(ResourceDialogComponent, {
      width: '420px',
      data: { resource }
    });
    ref.afterClosed().subscribe((result) => {
      if (result) {
        this.load();
      }
    });
  }

  toggleArchive(resource: MeteredResource): void {
    const archiving = resource.status === 'ACTIVE';
    const ref = this.dialog.open(ConfirmDialogComponent, {
      data: {
        title: archiving ? 'Archive resource' : 'Reactivate resource',
        message: archiving
          ? `Archive "${resource.name}"? It will stop accepting new usage events.`
          : `Reactivate "${resource.name}"?`,
        confirmLabel: archiving ? 'Archive' : 'Reactivate',
        danger: archiving
      }
    });
    ref.afterClosed().subscribe((confirmed) => {
      if (!confirmed) {
        return;
      }
      this.resourcesService.update(resource.id, { status: archiving ? 'ARCHIVED' : 'ACTIVE' }).subscribe({
        next: () => {
          this.snackbar.showInfo(`"${resource.name}" ${archiving ? 'archived' : 'reactivated'}.`);
          this.load();
        }
      });
    });
  }
}
