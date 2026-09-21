import { Instant, Uuid } from './common.models';

export type NotificationStatus = 'SENT' | 'FAILED';

/** Row of GET /v1/notifications. */
export interface NotificationRecord {
  id: Uuid;
  /** Event type, e.g. `invoice.issued`. */
  type: string;
  recipient: string;
  subject: string;
  status: NotificationStatus;
  created_at: Instant;
}
