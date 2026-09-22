import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatDialog } from '@angular/material/dialog';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatChipsModule } from '@angular/material/chips';
import { AuthService } from '../../core/services/auth.service';
import { UsersService } from '../../core/services/users.service';
import { SnackbarService } from '../../core/services/snackbar.service';
import { ConfirmDialogComponent } from '../../shared/components/confirm-dialog.component';
import { Role, User } from '../../core/models';
import { InviteUserDialogComponent } from './invite-user-dialog.component';

@Component({
  selector: 'app-users',
  standalone: true,
  imports: [DatePipe, MatTableModule, MatButtonModule, MatIconModule, MatSelectModule, MatTooltipModule, MatChipsModule],
  templateUrl: './users.component.html',
  styleUrl: './users.component.scss'
})
export class UsersComponent implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly usersService = inject(UsersService);
  private readonly dialog = inject(MatDialog);
  private readonly snackbar = inject(SnackbarService);

  readonly users = signal<User[]>([]);
  readonly loading = signal(true);
  readonly savingId = signal<string | null>(null);
  readonly columns = ['email', 'role', 'status', 'created_at', 'actions'];
  readonly roles: Role[] = ['TENANT_ADMIN', 'TENANT_USER'];

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.usersService.list().subscribe({
      next: (items) => {
        this.users.set(items);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  isSelf(user: User): boolean {
    return user.id === this.auth.currentUser()?.id;
  }

  changeRole(user: User, role: Role): void {
    if (role === user.role) {
      return;
    }
    this.savingId.set(user.id);
    this.usersService.update(user.id, { role }).subscribe({
      next: (updated) => {
        this.users.update((list) => list.map((u) => (u.id === updated.id ? updated : u)));
        this.savingId.set(null);
      },
      error: () => this.savingId.set(null)
    });
  }

  toggleStatus(user: User): void {
    const nextStatus = user.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE';
    const ref = this.dialog.open(ConfirmDialogComponent, {
      data: {
        title: nextStatus === 'DISABLED' ? 'Disable user' : 'Enable user',
        message: `${nextStatus === 'DISABLED' ? 'Disable' : 'Enable'} ${user.email}?`,
        confirmLabel: nextStatus === 'DISABLED' ? 'Disable' : 'Enable',
        danger: nextStatus === 'DISABLED'
      }
    });
    ref.afterClosed().subscribe((confirmed) => {
      if (!confirmed) {
        return;
      }
      this.savingId.set(user.id);
      this.usersService.update(user.id, { status: nextStatus }).subscribe({
        next: (updated) => {
          this.users.update((list) => list.map((u) => (u.id === updated.id ? updated : u)));
          this.savingId.set(null);
        },
        error: () => this.savingId.set(null)
      });
    });
  }

  openInvite(): void {
    const ref = this.dialog.open(InviteUserDialogComponent, { width: '420px' });
    ref.afterClosed().subscribe((created: User | undefined) => {
      if (created) {
        this.snackbar.showInfo(`Invited ${created.email}.`);
        this.load();
      }
    });
  }
}
