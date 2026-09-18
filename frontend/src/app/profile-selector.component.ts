import {Component, ChangeDetectorRef, DestroyRef, ElementRef, ViewChild, effect, inject, input, signal} from '@angular/core';
import {Status} from './models';
import {sendCommand} from './dom-handlers';

@Component({selector: 'dev-profile-selector', standalone: true, template: `
  @if(status()?.profiles; as profiles) {
    <div class="profile-picker">
      <label for="dev-profile">Dev profile</label>
      <div class="profile-select">
        <i class="bi bi-sliders2" aria-hidden="true"></i>
        <select #select id="dev-profile" [value]="profiles.active || ''"
          [disabled]="busy() || !connected() || status()?.maintenance?.busy || !profiles.switchSupported || profiles.available.length < 2"
          (change)="choose(select.value); select.value = profiles.active || ''">
          @if(!profiles.active) {<option value="">Default</option>}
          @if(profiles.active && !profiles.available.includes(profiles.active)) {<option [value]="profiles.active">{{profiles.active}}</option>}
          @for(profile of profiles.available; track profile) {<option [value]="profile">{{profile}}</option>}
        </select>
      </div>
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
  :host {display:block;min-width:0;}
  .profile-picker {margin:-6px 0 24px;padding:12px;background:var(--dashboard-panel-soft);border-radius:12px;}
  label {display:block;margin-bottom:6px;font-size:12px;font-weight:600;color:var(--dashboard-muted);}
  .profile-select {display:flex;align-items:center;gap:8px;color:var(--dashboard-active);}
  select {min-width:0;flex:1;width:100%;padding:4px 0;border:0;background:transparent;color:var(--dashboard-text);font:inherit;cursor:pointer;}
  option {background:var(--dashboard-surface);color:var(--dashboard-text);}
  select:disabled {cursor:default;}
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
  private readonly elementRef = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly changes = inject(ChangeDetectorRef);
  private timeout?: ReturnType<typeof setTimeout>;
  @ViewChild('dialog') dialog?: ElementRef<HTMLDialogElement>;
  @ViewChild('select') select?: ElementRef<HTMLSelectElement>;
  constructor() {
    effect(() => {
      if (this.busy() && this.connected() && this.status()?.profiles?.active === this.pending()) {
        clearTimeout(this.timeout);
        this.busy.set(false);
        sendCommand(this.elementRef.nativeElement, 'refreshApplication');
      }
    });
    inject(DestroyRef).onDestroy(() => clearTimeout(this.timeout));
  }
  choose(profile: string) {
    if (this.busy() || profile === this.status()?.profiles?.active || !this.status()?.profiles?.available.includes(profile)) return;
    this.chosen.set(profile);
    this.changes.detectChanges();
    this.dialog?.nativeElement.showModal();
  }
  restoreFocus() { this.select?.nativeElement.focus(); }
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
