import {DestroyRef, Injectable, computed, inject, signal} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {firstValueFrom, timeout} from 'rxjs';

export type AccountStatus = {
  state: 'signed_out' | 'signing_in' | 'signed_in' | 'sign_in_required' | 'unavailable';
  displayName?: string; email?: string; accountUrl?: string; environment?: string;
  expiresAt?: number; renewable?: boolean; operationId?: string; message?: string;
  dashboardUrl?: string; sessionsUrl?: string; pendingAction?: 'sign_out_everywhere';
  issue?: string;
};
export type AccountAction = 'login' | 'login-another' | 'logout' | 'cancel' | 'refresh' | 'logout-everywhere' | 'cancel-browser' | 'check' | 'check-interactive';
type Reply = {account?: AccountStatus; error?: {code: string; message: string}};

export function accountLink(value?: string): string | undefined {
  try {
    const url = new URL(value || '');
    const local = url.hostname === 'localhost' || url.hostname.endsWith('.localhost') || url.hostname === '127.0.0.1' || url.hostname === '[::1]';
    return !url.username && !url.password && (url.protocol === 'https:' || url.protocol === 'http:' && local) ? url.href : undefined;
  } catch { return undefined; }
}

/** One shared projection; status and startup never inspect credentials or contact Cloud. */
@Injectable({providedIn: 'root'})
export class AccountConnection {
  readonly account = signal<AccountStatus | undefined>(undefined);
  readonly message = signal('');
  readonly issue = signal('');
  readonly busy = signal(false);
  readonly activeAction = signal<AccountAction | undefined>(undefined);
  readonly signedIn = computed(() => this.account()?.state === 'signed_in');
  private readonly http = inject(HttpClient);
  private timer?: ReturnType<typeof setTimeout>;
  private statusRequest?: Promise<void>;
  private started = false;
  private destroyed = false;
  private request = 0;
  private readonly returned = () => {if(document.visibilityState !== 'hidden') void this.load(true);};

  constructor() {
    window.addEventListener('focus', this.returned);
    document.addEventListener('visibilitychange', this.returned);
    inject(DestroyRef).onDestroy(() => {
      this.destroyed = true; ++this.request; clearTimeout(this.timer);
      window.removeEventListener('focus', this.returned);
      document.removeEventListener('visibilitychange', this.returned);
    });
  }
  initialize() {if(!this.started) {this.started = true;void this.load();}}
  async load(checkPending = false) {
    if(this.busy() || this.destroyed) return;
    const pending = this.statusRequest ||= this.call('status');
    try {await pending;} finally {if(this.statusRequest === pending) this.statusRequest = undefined;}
  }
  async act(action: AccountAction) {
    if(this.busy() || this.destroyed) return;
    this.busy.set(true);
    this.activeAction.set(action);
    try {await this.call(action);} finally {this.busy.set(false);this.activeAction.set(undefined);}
  }
  private async call(action: string) {
    const request = ++this.request;
    clearTimeout(this.timer);
    try {
      const body = ['login','login-another','logout-everywhere'].includes(action)
        ? {returnTo: location.origin + location.pathname + location.hash} : {};
      const reply = await firstValueFrom(this.http.post<Reply>(`account/${action}`, body, {headers: {'X-Fluxzero-Console': '1'}})
        .pipe(timeout(action === 'status' ? 3000 : 25000)));
      if(this.destroyed || request !== this.request) return;
      if(reply.account) this.account.set(reply.account);
      else if(reply.error && !this.account()) this.account.set({state:'unavailable'});
      this.message.set(reply.error?.message || '');
      this.issue.set(reply.error?.code || reply.account?.issue || '');
    } catch {
      if(!this.destroyed && request === this.request) {
        this.message.set('Account features are unavailable. Try again shortly.');
        if(!this.account()) this.account.set({state:'unavailable'});
      }
    }
    if(!this.destroyed && request === this.request && (this.account()?.state === 'signing_in' || this.account()?.pendingAction === 'sign_out_everywhere'))
      this.timer = setTimeout(() => void this.load(), 1000);
  }
}
