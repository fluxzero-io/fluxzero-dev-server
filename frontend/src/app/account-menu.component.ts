import {ChangeDetectorRef, Component, ElementRef, HostListener, OnDestroy, OnInit, ViewChild, computed, inject, signal} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {firstValueFrom, timeout} from 'rxjs';

export type AccountStatus = {
  state: 'signed_out' | 'signing_in' | 'signed_in' | 'sign_in_required' | 'unavailable';
  displayName?: string; email?: string; accountUrl?: string; environment?: string;
  expiresAt?: number; renewable?: boolean; operationId?: string; message?: string;
};
type Reply = {account?: AccountStatus; error?: {code: string; message: string}};

@Component({selector: 'dev-account-menu', standalone: true, template: `
  <button #trigger class="icon-button account-trigger" type="button" [attr.aria-label]="label()" [title]="tooltip()"
    aria-haspopup="menu" [attr.aria-expanded]="open()" aria-controls="dev-account-menu" (click)="toggle()">
    <i class="bi" [class.bi-person]="!signedIn()" [class.bi-person-fill]="signedIn()" aria-hidden="true"></i>
  </button>
  @if(open()) {
    <div #panel id="dev-account-menu" class="account-menu" role="menu" aria-label="Fluxzero account">
      @if(signedIn()) {
        <div class="account-identity" role="presentation"><strong>{{account()?.displayName || 'Fluxzero account'}}</strong>
          @if(account()?.email) {<span>{{account()?.email}}</span>}
        </div>
      }
      @if(message() || account()?.message) {<p class="account-message" role="status">{{message() || account()?.message}}</p>}
      @if(account()?.state === 'signing_in') {
        <p class="account-message" role="status">Finish signing in in your browser.</p>
        <button type="button" role="menuitem" tabindex="-1" [disabled]="busy()" (click)="act('cancel')">Cancel sign-in</button>
      } @else if(signedIn()) {
        @if(accountUrl(); as url) {<a role="menuitem" tabindex="-1" [href]="url" target="_blank" rel="noopener noreferrer" (click)="close(true)"><i class="bi bi-person" aria-hidden="true"></i><span>Profile</span><i class="bi bi-box-arrow-up-right" aria-hidden="true"></i></a>}
        @if(issue() === 'storage_locked') {<button type="button" role="menuitem" tabindex="-1" [disabled]="busy()" (click)="act('refresh')">Unlock secure storage</button>}
        <button type="button" role="menuitem" tabindex="-1" [disabled]="busy()" (click)="act('logout')"><i class="bi bi-box-arrow-right" aria-hidden="true"></i>Sign out on this computer</button>
      } @else {
        <button type="button" role="menuitem" tabindex="-1" [disabled]="busy()" (click)="act('login')"><i class="bi bi-box-arrow-in-right" aria-hidden="true"></i>{{busy() ? 'Opening sign-in…' : account()?.state === 'sign_in_required' ? 'Sign in again' : 'Sign in'}}</button>
      }
      @if(message() && issue() !== 'storage_locked') {<button type="button" role="menuitem" tabindex="-1" [disabled]="busy()" (click)="load()">Try again</button>}
    </div>
  }
`, styles: `
  :host {display:block;position:relative;flex-shrink:0;}
  .account-trigger {font-size:20px;}
  .account-trigger[aria-expanded=true] {background:var(--dashboard-active-soft);color:var(--dashboard-active);}
  .account-menu {position:absolute;z-index:60;bottom:calc(100% + 10px);left:0;width:min(270px,calc(100vw - 48px));
    box-sizing:border-box;padding:6px;display:flex;flex-direction:column;gap:4px;color:var(--dashboard-text);
    background:var(--dashboard-popover-bg);border:1px solid var(--dashboard-border);border-radius:10px;
    box-shadow:var(--dashboard-popover-shadow);font-size:14px;line-height:1.5;}
  .account-identity {display:flex;flex-direction:column;gap:2px;padding:8px 10px 12px;margin-bottom:4px;
    border-bottom:1px solid var(--dashboard-border);overflow-wrap:anywhere;}
  .account-identity span {color:var(--dashboard-muted);font-size:12px;}
  .account-message {margin:0;padding:8px 10px;font-size:13px;}
  [role=menuitem] {display:flex;align-items:center;gap:10px;width:100%;box-sizing:border-box;min-height:38px;padding:8px 10px;
    color:var(--dashboard-text);background:transparent;border:0;border-radius:5px;text-decoration:none;text-align:left;font:inherit;font-size:13px;}
  [role=menuitem]:hover {background:var(--dashboard-panel-soft);}
  [role=menuitem] span {flex:1;}
  [role=menuitem] i {font-size:16px;}
  button:focus-visible,a:focus-visible {outline:2px solid var(--dashboard-active);outline-offset:3px;}
  button:disabled {opacity:.65;cursor:wait;}
  @media(pointer:coarse) {.account-trigger {width:44px;height:44px;}[role=menuitem] {min-height:44px;}}
`})
export class AccountMenuComponent implements OnInit, OnDestroy {
  readonly open = signal(false);
  readonly account = signal<AccountStatus | undefined>(undefined);
  readonly message = signal('');
  readonly issue = signal('');
  readonly busy = signal(false);
  readonly signedIn = computed(() => this.account()?.state === 'signed_in');
  readonly label = computed(() => 'Fluxzero account — ' + (this.signedIn() ? this.account()?.displayName || 'signed in' : this.account()?.state === 'signing_in' ? 'signing in' : 'signed out'));
  readonly tooltip = computed(() => this.signedIn() ? (this.account()?.displayName || 'Signed in') + ' — Fluxzero account' : this.account()?.state === 'signing_in' ? 'Signing in to Fluxzero' : 'Sign in to Fluxzero');
  private readonly http = inject(HttpClient);
  private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly changeDetector = inject(ChangeDetectorRef);
  @ViewChild('trigger') trigger?: ElementRef<HTMLButtonElement>;
  @ViewChild('panel') panel?: ElementRef<HTMLElement>;
  private timer?: ReturnType<typeof setTimeout>;
  private destroyed = false;
  private request = 0;

  ngOnInit() { void this.load(); }
  ngOnDestroy() { this.destroyed = true; clearTimeout(this.timer); }

  toggle() {
    if(this.open()) this.close();
    else {
      this.open.set(true);
      void this.load();
      this.changeDetector.detectChanges();
      this.items()[0]?.focus();
    }
  }

  accountUrl(): string | undefined {
    try {
      const url = new URL(this.account()?.accountUrl || '');
      const local = url.hostname === 'localhost' || url.hostname.endsWith('.localhost') || url.hostname === '127.0.0.1' || url.hostname === '[::1]';
      return !url.username && !url.password && (url.protocol === 'https:' || url.protocol === 'http:' && local) ? url.href : undefined;
    } catch { return undefined; }
  }

  @HostListener('window:focus') async load() { if(!this.busy()) await this.call('status'); }
  async act(action: 'login' | 'logout' | 'cancel' | 'refresh') {
    if(this.busy()) return;
    this.busy.set(true);
    try { await this.call(action); } finally {
      this.busy.set(false);
      if(!this.destroyed && this.open()) {this.changeDetector.detectChanges();this.items()[0]?.focus();}
    }
  }

  private async call(action: string) {
    const request = ++this.request;
    clearTimeout(this.timer);
    try {
      const reply = await firstValueFrom(this.http.post<Reply>(`account/${action}`, {}, {headers: {'X-Fluxzero-Console': '1'}})
        .pipe(timeout(action === 'status' ? 3000 : 15000)));
      if(this.destroyed || request !== this.request) return;
      const focused = this.panel?.nativeElement.contains(document.activeElement);
      if(reply.account) this.account.set(reply.account);
      this.message.set(reply.error?.message || '');
      this.issue.set(reply.error?.code || '');
      if(focused && this.open()) {
        this.changeDetector.detectChanges();
        if(!this.panel?.nativeElement.contains(document.activeElement)) this.items()[0]?.focus();
      }
    } catch {
      if(!this.destroyed && request === this.request && this.open())
        this.message.set('Account features are unavailable. Try again shortly.');
    }
    if(!this.destroyed && request === this.request && this.account()?.state === 'signing_in')
      this.timer = setTimeout(() => void this.load(), 1000);
  }

  @HostListener('document:click', ['$event'])
  @HostListener('document:focusin', ['$event'])
  dismissOutside(event: Event) {
    if(!this.element.nativeElement.contains(event.target as Node)) this.close();
  }
  close(restoreFocus = false) {this.open.set(false);if(restoreFocus) this.trigger?.nativeElement.focus();}
  private items() {return Array.from(this.panel?.nativeElement.querySelectorAll<HTMLElement>('[role="menuitem"]:not(:disabled)') || []);}
  @HostListener('keydown', ['$event']) onKeydown(event: KeyboardEvent) {
    if(event.key === 'Tab') {if(this.open()) this.close(true);return;}
    if(event.key === 'Escape' && this.open()) {
      event.preventDefault(); event.stopPropagation(); this.close(true);return;
    }
    if(!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    if(!this.open()) {this.toggle();return;}
    const items = this.items(), current = items.indexOf(document.activeElement as HTMLElement);
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1 : (current + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length;
    items[next]?.focus();
  }
}
