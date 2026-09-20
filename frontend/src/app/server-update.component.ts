import {Component, ElementRef, ViewChild, inject, input, signal, effect} from '@angular/core';
import {Status} from './models';
import {sendCommand} from './dom-handlers';

@Component({selector:'dev-server-update', standalone:true, template:`
  @if(status()?.update?.status === 'available' || pending()) {
    <button #trigger type="button" class="update-button" [disabled]="disabled()" (click)="open()"
      [title]="'Dev Server ' + (status()?.update?.latestVersion || selected())">
      <i class="bi bi-arrow-repeat" aria-hidden="true"></i>{{pending() ? 'Updating…' : 'Update & restart'}}
    </button>
  }
  @if(error() || status()?.update?.error) {<small role="alert">{{error() || status()?.update?.error}}</small>}
  <dialog #dialog class="maintenance-confirm" aria-labelledby="update-title" aria-describedby="update-description" (close)="restoreFocus()">
    <h2 id="update-title">Update Dev Server?</h2>
    <p id="update-description">Install version {{selected()}} and restart this workspace.<br>
      <strong>This resets local app data and monitoring history.</strong> Startup commands run again.
      Your code and Progress are kept.</p>
    <div class="dialog-actions">
      <button type="button" class="secondary-button dialog-cancel" (click)="dialog.close()" autofocus>Cancel</button>
      <button type="button" class="primary-button" [disabled]="disabled() || selected() !== status()?.update?.latestVersion" (click)="confirm()">Update & restart</button>
    </div>
  </dialog>`, styles:`
    :host {display:block;min-width:0;}
    .update-button {display:flex;align-items:center;gap:8px;border:0;border-radius:7px;padding:7px 9px;margin-bottom:8px;
      color:var(--dashboard-muted);background:var(--dashboard-action-bg);font-size:12px;max-width:100%;}
    .update-button:hover:not(:disabled) {background:var(--dashboard-action-hover);color:var(--dashboard-text);}
    .update-button:focus-visible {outline:2px solid var(--dashboard-active);outline-offset:2px;}
    .update-button:disabled {opacity:.6;cursor:default;}
    small {display:block;color:var(--dashboard-muted);font-size:12px;margin:6px 0;}
  `})
export class ServerUpdateComponent {
  status=input<Status>(); connected=input(false);
  selected=signal(''); pending=signal(false); error=signal('');
  private previousError=signal('');
  private previousAttempt=signal<string|undefined>(undefined);
  private readonly element=inject<ElementRef<HTMLElement>>(ElementRef);
  @ViewChild('dialog') dialog!:ElementRef<HTMLDialogElement>;
  @ViewChild('trigger') trigger?:ElementRef<HTMLButtonElement>;
  constructor() { effect(()=> {
    if(!this.pending()) return;
    const status=this.status();
    if(status?.versions?.devServer === this.selected()) this.pending.set(false);
    const error=status?.maintenance?.error || status?.update?.error;
    if(error && (error !== this.previousError() || status?.update?.attemptId !== this.previousAttempt())) {this.error.set(error);this.pending.set(false);}
    if(!error) this.previousError.set('');
  }); }
  disabled() {return this.pending() || !this.connected() || !!this.status()?.maintenance?.busy || !!this.status()?.maintenance?.workspaceStopped;}
  open() {
    if(this.disabled()) return;
    this.selected.set(this.status()?.update?.latestVersion || '');
    this.error.set('');this.dialog.nativeElement.showModal();
  }
  restoreFocus() {this.trigger?.nativeElement.focus();}
  async confirm() {
    if(this.disabled() || this.selected() !== this.status()?.update?.latestVersion) return;
    this.previousAttempt.set(this.status()?.update?.attemptId);
    this.previousError.set(this.status()?.maintenance?.error || this.status()?.update?.error || '');
    this.pending.set(true);this.dialog.nativeElement.close();
    try {await sendCommand<Promise<void>>(this.element.nativeElement,'updateDevServer',this.selected());}
    catch(error:any) {this.error.set(error?.error?.error || 'Could not start the update. Try again.');this.pending.set(false);}
  }
}
