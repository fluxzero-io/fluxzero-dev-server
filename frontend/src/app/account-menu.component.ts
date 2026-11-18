import {ChangeDetectorRef, Component, ElementRef, HostListener, OnInit, OnDestroy, ViewChild, computed, inject, input, signal} from '@angular/core';
import {AccountConnection, AccountAction, accountLink} from './account-connection';
export type {AccountStatus} from './account-connection';

@Component({selector: 'dev-account-menu', standalone: true, template: `
  <button #trigger class="icon-button account-trigger" type="button" [attr.aria-label]="label()" [title]="tooltip()"
    aria-haspopup="menu" [attr.aria-expanded]="open()" aria-controls="dev-account-menu" (click)="toggle()">
    <i class="bi" [class.bi-person]="!signedIn()" [class.bi-person-fill]="signedIn()" aria-hidden="true"></i>
  </button>
  @if(open()) {
    <div #panel id="dev-account-menu" class="account-menu" [class.account-menu-below]="placement() === 'below'" role="menu" aria-label="Fluxzero account">
      @if(signedIn()) {
        <div class="account-identity" role="group" aria-label="Signed-in account"><div class="account-identity-text"><strong>{{account()?.displayName || 'Fluxzero account'}}</strong>
          @if(account()?.email) {<span>{{account()?.email}}</span>}
          </div><button type="button" class="switch-account" role="menuitem" tabindex="-1" title="Use another account" aria-label="Use another account" [disabled]="busy() || signingOutEverywhere()" (click)="act('login-another')"><i class="bi bi-arrow-left-right" aria-hidden="true"></i></button>
        </div>
      }
      @if(message() || account()?.message && !signingOutEverywhere()) {<p class="account-message" role="status">{{message() || account()?.message}}</p>}
      @if(account()?.state === 'signing_in') {
        <p class="account-message" role="status">Finish signing in in your browser.</p>
        <button type="button" role="menuitem" tabindex="-1" [disabled]="busy()" (click)="act('cancel')">Cancel sign-in</button>
      } @else if(signedIn()) {
        @if(accountUrl(); as url) {<a role="menuitem" tabindex="-1" [href]="url" target="_blank" rel="noopener noreferrer" (click)="close(true)"><i class="bi bi-person" aria-hidden="true"></i><span>Profile</span></a>}
        @if(dashboardUrl(); as url) {<a role="menuitem" tabindex="-1" [href]="url" target="_blank" rel="noopener noreferrer" (click)="close(true)"><i class="bi bi-speedometer2" aria-hidden="true"></i><span>Dashboard</span></a>}
        @if(issue() === 'storage_locked') {<button type="button" role="menuitem" tabindex="-1" [disabled]="busy()" (click)="act('check-interactive')">Unlock secure storage</button>}
        @if(signingOutEverywhere()) {
          <p class="account-progress" role="status"><span class="spinner-border spinner-border-sm" aria-hidden="true"></span><span>Confirm sign-out in your browser…</span></p>
        }
        <div class="signout-actions" role="group" aria-label="Sign-out options" (pointerleave)="leaveMore()" (pointerenter)="keepMore()">
          <button type="button" role="menuitem" tabindex="-1" title="Sign out on this computer only" [disabled]="busy()" [attr.aria-busy]="connection.activeAction() === 'logout' ? 'true' : null" (click)="act('logout')">
            @if(connection.activeAction() === 'logout') {<span class="spinner-border spinner-border-sm" aria-hidden="true"></span>} @else {<i class="bi bi-box-arrow-right" aria-hidden="true"></i>}
            <span>{{connection.activeAction() === 'logout' ? 'Signing out…' : 'Sign out'}}</span>
          </button>
          <button #moreTrigger class="more-signout" type="button" role="menuitem" tabindex="-1" title="More sign-out options" aria-label="More sign-out options" aria-haspopup="menu" [attr.aria-expanded]="more()" aria-controls="more-signout-menu" [disabled]="busy() || signingOutEverywhere()" (pointerenter)="hoverMore($event)" (click)="toggleMore()"><i class="bi bi-three-dots" aria-hidden="true"></i></button>
          @if(more()) {<div id="more-signout-menu" role="menu" aria-label="More sign-out options" class="more-signout-menu" (pointerenter)="keepMore()">
            <button type="button" role="menuitem" tabindex="-1" [disabled]="busy()" (click)="act('logout-everywhere')"><i class="bi bi-globe" aria-hidden="true"></i>Sign out everywhere…</button>
          </div>}
        </div>
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
  .account-menu {position:absolute;z-index:60;bottom:calc(100% + 10px);left:0;width:min(300px,calc(var(--sidebar-width,300px) - 44px),calc(100vw - 48px));
    box-sizing:border-box;padding:6px;display:flex;flex-direction:column;gap:4px;color:var(--dashboard-text);
    background:var(--dashboard-popover-bg);border:1px solid var(--dashboard-border);border-radius:10px;
    box-shadow:var(--dashboard-popover-shadow);font-size:14px;line-height:1.5;}
  .account-menu.account-menu-below {top:calc(100% + 10px);bottom:auto;left:auto;right:0;width:min(300px,calc(100vw - 32px));}
  .account-identity {display:flex;align-items:center;gap:8px;padding:8px 10px 12px;margin-bottom:4px;
    border-bottom:1px solid var(--dashboard-border);overflow-wrap:anywhere;}
  .account-identity-text {display:flex;flex-direction:column;gap:2px;flex:1;min-width:0;}
  .account-identity-text > span {color:var(--dashboard-muted);font-size:12px;}
  .account-message {margin:0;padding:8px 10px;font-size:13px;}
  [role=menuitem] {display:flex;align-items:center;gap:10px;width:100%;box-sizing:border-box;min-height:38px;padding:8px 10px;
    color:var(--dashboard-text);background:transparent;border:0;border-radius:5px;text-decoration:none;text-align:left;font:inherit;font-size:13px;}
  .signout-actions {display:flex;align-items:stretch;position:relative;}
  .account-identity .switch-account {flex:0 0 36px;width:36px;justify-content:center;padding:8px;}
  .account-progress {display:flex;align-items:center;gap:10px;margin:0;padding:8px 10px;color:var(--dashboard-muted);font-size:13px;}
  .account-progress .spinner-border {flex-shrink:0;}
  .signout-actions > button:first-child {flex:1;min-width:0;}
  .signout-actions .more-signout {flex:0 0 36px;width:36px;justify-content:center;padding:8px;}
  .more-signout-menu {position:absolute;bottom:calc(100% + 6px);right:0;z-index:1;width:230px;max-width:100%;padding:6px;
    background:var(--dashboard-popover-bg);border:1px solid var(--dashboard-border);border-radius:8px;box-shadow:var(--dashboard-popover-shadow);}
  .more-signout-menu::after {content:"";position:absolute;left:0;right:0;bottom:-8px;height:8px;}
  .more-signout[aria-expanded=true] {background:var(--dashboard-active-soft);}
  [role=menuitem]:hover {background:var(--dashboard-panel-soft);}
  [role=menuitem] span {flex:1;}
  [role=menuitem] i {font-size:16px;}
  button:focus-visible,a:focus-visible {outline:2px solid var(--dashboard-active);outline-offset:3px;}
  button:disabled {opacity:.65;cursor:wait;}
  @media(pointer:coarse) {.signout-actions .more-signout,.account-identity .switch-account {flex-basis:44px;width:44px;}.account-trigger {width:44px;height:44px;}[role=menuitem] {min-height:44px;}}
`})
export class AccountMenuComponent implements OnInit, OnDestroy {
  readonly placement = input<'above'|'below'>('above');
  private destroyed = false;
  readonly open = signal(false);
  readonly more = signal(false);
  readonly connection = inject(AccountConnection);
  readonly account = this.connection.account;
  readonly message = this.connection.message;
  readonly issue = this.connection.issue;
  readonly busy = this.connection.busy;
  readonly signedIn = this.connection.signedIn;
  readonly signingOutEverywhere = computed(() => this.account()?.pendingAction === 'sign_out_everywhere');
  private hoverTimer?: ReturnType<typeof setTimeout>;
  readonly label = computed(() => 'Fluxzero account — ' + (this.signedIn() ? this.account()?.displayName || 'signed in' : this.account()?.state === 'signing_in' ? 'signing in' : 'signed out'));
  readonly tooltip = computed(() => this.signedIn() ? (this.account()?.displayName || 'Signed in') + ' — Fluxzero account' : this.account()?.state === 'signing_in' ? 'Signing in to Fluxzero' : 'Sign in to Fluxzero');
  private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly changeDetector = inject(ChangeDetectorRef);
  @ViewChild('trigger') trigger?: ElementRef<HTMLButtonElement>;
  @ViewChild('moreTrigger') moreTrigger?: ElementRef<HTMLButtonElement>;
  @ViewChild('panel') panel?: ElementRef<HTMLElement>;

  ngOnInit() {this.connection.initialize();}
  toggle() {
    if(this.open()) this.close();
    else {this.open.set(true);void this.load();this.changeDetector.detectChanges();this.items()[0]?.focus();}
  }
  ngOnDestroy() {this.destroyed=true;this.close();}
  keepMore() {clearTimeout(this.hoverTimer);}
  leaveMore() {this.hoverTimer=setTimeout(() => {if(!this.panel?.nativeElement.querySelector('#more-signout-menu')?.contains(document.activeElement)) this.more.set(false);},150);}
  hoverMore(event: PointerEvent) {if(event.pointerType === 'mouse' && !this.busy() && !this.signingOutEverywhere()) {this.keepMore();this.more.set(true);}}
  toggleMore() {
    this.keepMore();
    this.more.set(true);
    this.changeDetector.detectChanges();
    if(this.more()) this.panel?.nativeElement.querySelector<HTMLElement>('#more-signout-menu [role=menuitem]')?.focus();
  }
  accountUrl() {return accountLink(this.account()?.accountUrl);}
  dashboardUrl() {return accountLink(this.account()?.dashboardUrl);}
  async load() {await this.connection.load();this.retainFocus();}
  async act(action: AccountAction) {await this.connection.act(action);this.more.set(false);this.retainFocus();}
  private retainFocus() {
    if(this.destroyed || !this.open()) return;
    this.changeDetector.detectChanges();
    if(!this.panel?.nativeElement.contains(document.activeElement)) this.items()[0]?.focus();
  }
  @HostListener('document:click', ['$event'])
  @HostListener('document:focusin', ['$event'])
  dismissOutside(event: Event) {if(!this.element.nativeElement.contains(event.target as Node)) this.close();}
  close(restoreFocus = false) {this.keepMore();this.more.set(false);this.open.set(false);if(restoreFocus) this.trigger?.nativeElement.focus();}
  private items() {return Array.from(this.panel?.nativeElement.querySelectorAll<HTMLElement>('[role="menuitem"]:not(:disabled)') || []);}
  @HostListener('keydown', ['$event']) onKeydown(event: KeyboardEvent) {
    if(event.key === 'Tab') {if(this.open()) this.close(true);return;}
    if(event.key === 'Escape' && this.open()) {
      event.preventDefault();event.stopPropagation();
      if(this.more()) {this.more.set(false);this.changeDetector.detectChanges();this.moreTrigger?.nativeElement.focus();}
      else this.close(true);
      return;
    }
    if(!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    if(!this.open()) {this.toggle();return;}
    const items=this.items(),current=items.indexOf(document.activeElement as HTMLElement);
    const next=event.key==='Home'?0:event.key==='End'?items.length-1:(current+(event.key==='ArrowDown'?1:-1)+items.length)%items.length;
    items[next]?.focus();
  }
}
