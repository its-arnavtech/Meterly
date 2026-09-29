import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { NotificationsService } from '../../core/services/notifications.service';
import { NotificationRecord } from '../../core/models';

@Component({
  selector: 'app-notifications',
  standalone: true,
  imports: [DatePipe, MatTableModule, MatIconModule, MatChipsModule],
  templateUrl: './notifications.component.html',
  styleUrl: './notifications.component.scss'
})
export class NotificationsComponent implements OnInit {
  private readonly notificationsService = inject(NotificationsService);

  readonly notifications = signal<NotificationRecord[]>([]);
  readonly loading = signal(true);
  readonly columns = ['type', 'recipient', 'subject', 'status', 'created_at'];

  ngOnInit(): void {
    this.notificationsService.list().subscribe({
      next: (items) => {
        this.notifications.set(items);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }
}
