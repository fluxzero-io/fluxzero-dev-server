import {Injectable, InjectionToken, inject} from '@angular/core';
import {Environment, Status, TestOutputLine} from './models';
import {appendSample, ResourceSample} from './resource-history';

export const CONSOLE_SOCKET = new InjectionToken<(url: string) => WebSocket>('console websocket', {
  providedIn: 'root', factory: () => url => new WebSocket(url)
});
export interface ConsoleState {status: Status; environments: Environment[];}
export interface ConsoleUpdate {
  type: 'snapshot' | 'update' | 'heartbeat'; version: number; sequence: number;
  output?: {firstSequence:number; lines:TestOutputLine[]};
  status?: Partial<Status>; environments?: Environment[]; sample?: ResourceSample;
}

/** Dashboard-style push connection. Requests remain HTTP behind the DOM gateways. */
@Injectable({providedIn: 'root'})
export class ConsoleConnection {
  private readonly createSocket = inject(CONSOLE_SOCKET);
  private socket?: WebSocket;
  private retry?: ReturnType<typeof setTimeout>;
  private watchdog?: ReturnType<typeof setTimeout>;
  private delay = 1000;
  private destroyed = true;
  private sequence = -1;
  private state?: ConsoleState;
  private onUpdate?: (state: ConsoleState) => void;
  private onConnected?: (connected: boolean) => void;

  initialise(onUpdate: (state: ConsoleState) => void, onConnected: (connected: boolean) => void) {
    this.close();
    this.destroyed = false;
    this.delay = 1000;
    this.onUpdate = onUpdate;
    this.onConnected = onConnected;
    this.open();
  }

  close() {
    this.destroyed = true;
    clearTimeout(this.retry);
    clearTimeout(this.watchdog);
    if (this.socket) {
      this.socket.onclose = null;
      this.socket.onmessage = null;
      this.socket.onerror = null;
      this.socket.close();
    }
    this.socket = undefined;
  }

  private open() {
    if (this.destroyed) return;
    this.sequence = -1;
    this.state = undefined;
    this.onConnected?.(false);
    try {
      const url = new URL('updates', document.baseURI);
      url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
      const socket = this.socket = this.createSocket(url.href);
      const disconnected = () => {
        if (this.socket !== socket || this.destroyed) return;
        this.socket = undefined;
        socket.onclose = null;
        socket.onmessage = null;
        socket.onerror = null;
        socket.close();
        this.scheduleRetry();
      };
      socket.onclose = disconnected;
      socket.onerror = disconnected;
      socket.onmessage = event => {
        if (this.socket !== socket || this.destroyed) return;
        try {
          this.accept(JSON.parse(event.data));
          this.armWatchdog(disconnected);
        } catch { disconnected(); }
      };
      // Covers failed handshakes, missing snapshots and half-open connections.
      this.armWatchdog(disconnected);
    } catch { this.scheduleRetry(); }
  }

  private accept(update: ConsoleUpdate) {
    if (update.version !== 1 || !Number.isSafeInteger(update.sequence)) throw Error('Invalid console update');
    if (update.type === 'snapshot') {
      if (!update.status?.projectDirectory || !Array.isArray(update.status.resourceHistory) || !Array.isArray(update.environments)) throw Error('Invalid snapshot');
      this.state = {status: update.status as Status, environments: update.environments};
      this.sequence = update.sequence;
      this.delay = 1000;
      this.onUpdate?.(this.state);
      this.onConnected?.(true);
      return;
    }
    if (!this.state) throw Error('Snapshot required');
    if (update.type === 'heartbeat') {
      if (update.sequence !== this.sequence) throw Error('Missed update');
      return;
    }
    if (update.type !== 'update') throw Error('Unknown console update');
    if (update.sequence <= this.sequence) return;
    if (update.sequence !== this.sequence + 1) throw Error('Missed update');
    const status = {...this.state.status, ...update.status};
    if (update.sample) status.resourceHistory = appendSample(status.resourceHistory || [], update.sample);
    if (update.output) status.testOutput = update.output.firstSequence === 0 ? [] : [...(status.testOutput || []), ...update.output.lines]
      .filter(line => line.sequence >= update.output!.firstSequence).slice(-200);
    this.state = {status, environments: update.environments ?? this.state.environments};
    this.sequence = update.sequence;
    this.onUpdate?.(this.state);
  }

  private armWatchdog(disconnected: () => void) {
    clearTimeout(this.watchdog);
    this.watchdog = setTimeout(disconnected, 30_000);
  }

  private scheduleRetry() {
    clearTimeout(this.watchdog);
    clearTimeout(this.retry);
    this.onConnected?.(false);
    if (this.destroyed) return;
    this.retry = setTimeout(() => this.open(), this.delay);
    this.delay = Math.min(this.delay * 2, 30_000);
  }
}
