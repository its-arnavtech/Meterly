import {Injectable, signal} from '@angular/core';
import {Client, IMessage} from '@stomp/stompjs';
import {Session, UsageUpdate} from './models';

@Injectable({providedIn: 'root'})
export class UsageStreamService {
  readonly totalUnits = signal(0);
  readonly connected = signal(false);
  private client?: Client;
  connect(session: Session): void {
    void this.client?.deactivate();
    this.client = new Client({
      brokerURL: 'ws://127.0.0.1:8082/ws/usage', reconnectDelay: 1_000,
      connectHeaders: {Authorization: `Bearer ${session.accessToken}`},
      onConnect: () => {
        this.connected.set(true);
        this.client?.subscribe(`/topic/usage/${session.tenantId}`, message => this.applyMessage(message));
      },
      onWebSocketClose: () => this.connected.set(false)
    });
    this.client.activate();
  }
  seed(total: number): void { this.totalUnits.set(total); }
  disconnect(): void { this.connected.set(false); void this.client?.deactivate(); }
  applyMessage(message: Pick<IMessage, 'body'>): void {
    this.totalUnits.set((JSON.parse(message.body) as UsageUpdate).totalUnits);
  }
}
