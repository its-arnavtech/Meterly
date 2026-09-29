import { Injectable, inject, signal } from '@angular/core';
import { Client, IMessage, StompSubscription } from '@stomp/stompjs';
import { Observable, Subject } from 'rxjs';
import { AuthService } from './auth.service';
import { UsageUpdate } from '../models';

export type StompConnectionState = 'disconnected' | 'connecting' | 'connected' | 'error';

const MAX_RECONNECT_DELAY_MS = 30_000;
const BASE_RECONNECT_DELAY_MS = 1_000;

/**
 * STOMP-over-WebSocket client for the live usage feed (docs/api-contract.md §3 "WebSocket").
 * `ws(s)://<host>/ws/usage`, CONNECT header carries a bearer token (refreshed first if it's
 * close to expiry), subscribes `/topic/tenants/{tenant_id}/usage`, 10s heartbeats both ways,
 * and reconnects with exponential backoff (capped at 30s) on any drop that wasn't requested.
 */
@Injectable({ providedIn: 'root' })
export class UsageStompService {
  private readonly auth = inject(AuthService);

  private client: Client | null = null;
  private subscription: StompSubscription | null = null;
  private readonly updates = new Subject<UsageUpdate>();
  private reconnectAttempts = 0;
  private reconnectHandle: ReturnType<typeof setTimeout> | null = null;
  private manualDisconnect = true;

  readonly connectionState = signal<StompConnectionState>('disconnected');
  readonly usageUpdates$: Observable<UsageUpdate> = this.updates.asObservable();

  connect(): void {
    this.manualDisconnect = false;
    const tenantId = this.auth.currentUser()?.tenant_id;
    if (!tenantId) {
      this.connectionState.set('error');
      return;
    }
    this.connectionState.set('connecting');
    this.auth.ensureFreshToken().subscribe((token) => {
      if (this.manualDisconnect) {
        return;
      }
      if (!token) {
        this.connectionState.set('error');
        this.scheduleReconnect();
        return;
      }
      this.openClient(token, tenantId);
    });
  }

  disconnect(): void {
    this.manualDisconnect = true;
    if (this.reconnectHandle) {
      clearTimeout(this.reconnectHandle);
      this.reconnectHandle = null;
    }
    this.subscription?.unsubscribe();
    this.subscription = null;
    void this.client?.deactivate();
    this.client = null;
    this.connectionState.set('disconnected');
  }

  private openClient(token: string, tenantId: string): void {
    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const brokerURL = `${protocol}://${window.location.host}/ws/usage`;

    this.client = new Client({
      brokerURL,
      connectHeaders: { Authorization: `Bearer ${token}` },
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,
      // Reconnection is handled manually below so we can apply exponential backoff.
      reconnectDelay: 0,
      onConnect: () => {
        this.reconnectAttempts = 0;
        this.connectionState.set('connected');
        this.subscription =
          this.client?.subscribe(`/topic/tenants/${tenantId}/usage`, (message: IMessage) => {
            try {
              this.updates.next(JSON.parse(message.body) as UsageUpdate);
            } catch {
              // Ignore a malformed frame; the next one will still arrive.
            }
          }) ?? null;
      },
      onStompError: () => this.connectionState.set('error'),
      onWebSocketError: () => this.connectionState.set('error'),
      onWebSocketClose: () => {
        this.subscription = null;
        if (!this.manualDisconnect) {
          this.connectionState.set('disconnected');
          this.scheduleReconnect();
        }
      }
    });
    this.client.activate();
  }

  private scheduleReconnect(): void {
    if (this.reconnectHandle) {
      return;
    }
    const delay = Math.min(MAX_RECONNECT_DELAY_MS, BASE_RECONNECT_DELAY_MS * 2 ** this.reconnectAttempts);
    this.reconnectAttempts++;
    this.reconnectHandle = setTimeout(() => {
      this.reconnectHandle = null;
      if (!this.manualDisconnect) {
        this.connect();
      }
    }, delay);
  }
}
