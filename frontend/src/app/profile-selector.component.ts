import {Component, HostListener, computed, ChangeDetectorRef, DestroyRef, ElementRef, ViewChild, effect, inject, input, signal} from '@angular/core';
import {Status} from './models';
import {sendCommand} from './dom-handlers';

@Component({selector: 'dev-profile-selector', standalone: true, template: `
  @if(status()?.profiles; as profiles) {
    <div class="profile-picker">
      <div class="dashboard-section-label">Dev profile</div>
      <button #trigger class="profile-picker-button" type="button" aria-label="Choose dev profile" aria-haspopup="menu"
        aria-controls="dev-profile-menu" [attr.aria-expanded]="open()" [disabled]="disabled()" (click)="toggle()">
        <i class="bi bi-sliders2" aria-hidden="true"></i><span>{{profiles.active || 'Default'}}</span><i class="bi bi-chevron-down" aria-hidden="true"></i>
      </button>
      @if(open()) {
        <div id="dev-profile-menu" class="profile-menu" role="menu" aria-label="Dev profiles">
          @for(profile of profiles.available; track profile) {
            <button class="profile-option" type="button" role="menuitemradio" [attr.aria-checked]="profile === profiles.active"
              [class.active]="profile === profiles.active" (click)="choose(profile)">
              <span>{{profile}}</span>@if(profile === profiles.active) {<i class="bi bi-check2" aria-hidden="true"></i>}
            </button>
          }
        </div>
      }
      @if(busy()) {<small role="status">Switching to {{pending()}}…</small>}
      @if(error() || profiles.error) {<small role="alert">{{error() || profiles.error}}</small>}
    </div>
  }
  <dialog #dialog class="maintenance-confirm" aria-labelledby="profile-title" aria-describedby="profile-description" (close)="restoreFocus()">
    <h2 id="profile-title">Switch to {{chosen()}}?</h2>
    <p id="profile-description">This restarts the dev environment with the selected profile. In-memory application data will be reset and startup commands will run again.</p>
    <div class="dialog-actions">
      <button class="secondary-button" (click)="dialog.close()" autofocus>Cancel</button>
      <button class="primary-button" (click)="confirm()">Switch profile</button>
    </div>
  </dialog>
`, styles: `
  :host {display:block;position:relative;margin-bottom:20px;min-width:0;}
  .profile-picker-button {display:grid;grid-template-columns:16px minmax(0,1fr) 14px;align-items:center;gap:5px;width:100%;min-height:38px;
    padding:0 8px;border:1px solid var(--dashboard-border);border-radius:6px;background:var(--dashboard-surface);color:var(--dashboard-text);text-align:left;}
  .profile-picker-button span {overflow:hidden;font-size:15px;font-weight:700;text-overflow:ellipsis;white-space:nowrap;}
  .profile-picker-button i {color:var(--dashboard-control-icon);font-size:14px;}
  .profile-picker-button:hover {border-color:var(--dashboard-focus-border);}
  .profile-picker-button:focus-visible {outline:2px solid var(--dashboard-focus-border);}
  .profile-menu {position:absolute;top:calc(100% + 6px);left:0;z-index:30;width:100%;max-height:280px;overflow-y:auto;
    padding:6px;border:1px solid var(--dashboard-border);border-radius:8px;background:var(--dashboard-popover-bg);box-shadow:var(--dashboard-popover-shadow);}
  .profile-option {display:flex;align-items:center;justify-content:space-between;gap:12px;width:100%;padding:10px;border:0;border-radius:5px;
    background:transparent;color:var(--dashboard-text);text-align:left;font:inherit;}
  .profile-option span {min-width:0;overflow-wrap:anywhere;}
  .profile-option:hover,.profile-option.active {background:var(--dashboard-active-soft);}
  .profile-option.active {font-weight:600;}
  .profile-option i {color:var(--dashboard-active);}
  .profile-option:focus-visible {outline:2px solid var(--dashboard-focus-border);outline-offset:-2px;}
  small {display:block;margin-top:8px;color:var(--dashboard-muted);overflow-wrap:anywhere;}
  [role=alert] {color:var(--dashboard-danger-text);}
`})
export class ProfileSelectorComponent {
  readonly status = input<Status>();
  readonly connected = input(false);
  readonly chosen = signal('');
  readonly pending = signal('');
  readonly busy = signal(false);
  readonly error = signal('');
  readonly open = signal(false);
  readonly disabled = computed(() => this.busy() || !this.connected() || !!this.status()?.maintenance?.busy
    || !this.status()?.profiles?.switchSupported || (this.status()?.profiles?.available.length || 0) < 2);
  private readonly elementRef = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly changes = inject(ChangeDetectorRef);
  private timeout?: ReturnType<typeof setTimeout>;
  @ViewChild('dialog') dialog?: ElementRef<HTMLDialogElement>;
  @ViewChild('trigger') trigger?: ElementRef<HTMLButtonElement>;
  constructor() {
    effect(() => {
      if (this.disabled()) this.open.set(false);
      if (this.busy() && this.connected() && this.status()?.profiles?.active === this.pending()) {
        clearTimeout(this.timeout);
        this.busy.set(false);
        sendCommand(this.elementRef.nativeElement, 'refreshApplication');
      }
    });
    inject(DestroyRef).onDestroy(() => clearTimeout(this.timeout));
  }
  toggle() {
    if (this.disabled()) return;
    this.open.set(!this.open());
    if (this.open()) {
      this.changes.detectChanges();
      (this.elementRef.nativeElement.querySelector('[aria-checked="true"]') as HTMLElement | null)?.focus();
    }
  }
  choose(profile: string) {
    if (this.disabled() || !this.status()?.profiles?.available.includes(profile)) return;
    this.open.set(false);
    if (profile === this.status()?.profiles?.active) { this.restoreFocus(); return; }
    this.chosen.set(profile);
    this.changes.detectChanges();
    this.dialog?.nativeElement.showModal();
  }
  restoreFocus() { this.trigger?.nativeElement.focus(); }
  @HostListener('document:click', ['$event'])
  @HostListener('document:focusin', ['$event']) dismissOutside(event: Event) {
    if (!this.elementRef.nativeElement.contains(event.target as Node)) this.open.set(false);
  }
  @HostListener('window:blur') dismissOnBlur() { this.open.set(false); }
  @HostListener('keydown', ['$event']) keydown(event: KeyboardEvent) {
    if (!this.open()) {
      if (event.target === this.trigger?.nativeElement && ['ArrowDown', 'ArrowUp'].includes(event.key)) {
        event.preventDefault(); this.toggle();
      }
      return;
    }
    if (event.key === 'Escape') {
      event.preventDefault(); event.stopPropagation(); this.open.set(false); this.restoreFocus();
    } else if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
      event.preventDefault();
      const options = Array.from(this.elementRef.nativeElement.querySelectorAll<HTMLButtonElement>('.profile-option'));
      const index = options.indexOf(document.activeElement as HTMLButtonElement);
      const next = event.key === 'Home' ? 0 : event.key === 'End' ? options.length - 1
        : (index + (event.key === 'ArrowDown' ? 1 : -1) + options.length) % options.length;
      options[next]?.focus();
    }
  }
  async confirm() {
    if (this.busy() || !this.dialog?.nativeElement.open) return;
    this.dialog.nativeElement.close();
    this.busy.set(true); this.pending.set(this.chosen()); this.error.set('');
    this.timeout = setTimeout(() => {
      this.busy.set(false);
      this.error.set('The profile has not become available yet. Check the dev server logs.');
    }, 120_000);
    try { await sendCommand<Promise<void>>(this.elementRef.nativeElement, 'switchProfile', this.chosen()); }
    catch (error: any) {
      clearTimeout(this.timeout); this.busy.set(false);
      this.error.set(error?.error?.error || 'Unable to switch profile.');
    }
  }
}
