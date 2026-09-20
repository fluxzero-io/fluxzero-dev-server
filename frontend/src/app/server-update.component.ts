import {Component, ElementRef, ViewChild, inject, input, signal, effect, output} from '@angular/core';
import {Status} from './models';
import {sendCommand} from './dom-handlers';

@Component({selector:'dev-server-update', standalone:true, template:`
  @if(status()?.update?.status === 'available' || pending()) {
    <button #trigger type="button" class="update-button" [disabled]="disabled()" (click)="open()"
      [title]="'Devboard ' + (status()?.update?.latestVersion || selected())">
      <i class="bi bi-arrow-repeat" aria-hidden="true"></i>{{pending() ? 'Updating…' : 'Update available'}}
    </button>
  }
  @if(error() || status()?.update?.error) {<small role="alert">{{error() || status()?.update?.error}}</small>}
  <dialog #dialog class="maintenance-confirm" aria-labelledby="update-title" aria-describedby="update-versions update-description" (close)="restoreFocus()" (keydown)="$event.stopPropagation()">
    <h2 id="update-title">Update Devboard?</h2>
    <dl id="update-versions" class="update-versions">
      <dt>Current version:</dt><dd>{{status()?.versions?.devServer || status()?.update?.currentVersion || '—'}}</dd>
      <dt>New version:</dt><dd>{{selected()}}</dd>
    </dl>
    <p id="update-description">This resets local app data and monitoring history. Startup commands run again.
      Your code and progress are kept.</p>
    <div class="dialog-actions">
      <button type="button" class="secondary-button dialog-cancel" (click)="dialog.close()" autofocus>Cancel</button>
      <button type="button" class="primary-button" [disabled]="disabled() || selected() !== status()?.update?.latestVersion" (click)="confirm()">Update & restart</button>
    </div>
  </dialog>`, styles:`
    :host {display:block;min-width:0;}
    .update-button {display:flex;align-items:center;justify-content:center;gap:8px;border:0;border-radius:7px;
      width:100%;box-sizing:border-box;padding:10px 12px;margin-bottom:12px;
      color:#fff;background:#0d6efd;font-size:13px;font-weight:500;}
    .update-button:hover:not(:disabled) {background:#0b5ed7;}
    .update-button:active:not(:disabled) {background:#0a58ca;}
    .update-button:focus-visible {outline:2px solid var(--dashboard-active);outline-offset:2px;}
    .update-button:disabled {opacity:.6;cursor:default;}
    .update-versions {display:grid;grid-template-columns:max-content minmax(0,1fr);gap:6px 16px;margin:0 0 20px;
      color:var(--dashboard-muted);font-size:14px;line-height:1.6;}
    .update-versions dt,.update-versions dd {margin:0;font-weight:400;}
    .update-versions dd {color:var(--dashboard-text);overflow-wrap:anywhere;font-variant-numeric:tabular-nums;}
    small {display:block;color:var(--dashboard-muted);font-size:12px;margin:6px 0;}
  `})
export class ServerUpdateComponent {
  status=input<Status>(); connected=input(false);
  updated=output<string>();
  selected=signal(''); pending=signal(false); error=signal('');
  private previousError=signal('');
  private previousAttempt=signal<string|undefined>(undefined);
  private readonly element=inject<ElementRef<HTMLElement>>(ElementRef);
  @ViewChild('dialog') dialog!:ElementRef<HTMLDialogElement>;
  @ViewChild('trigger') trigger?:ElementRef<HTMLButtonElement>;
  constructor() { effect(()=> {
    if(!this.pending()) return;
    const status=this.status();
    const error=status?.maintenance?.error || status?.update?.error;
    if(error && (error !== this.previousError() || status?.update?.attemptId !== this.previousAttempt())) {this.error.set(error);this.pending.set(false);return;}
    if(!error) this.previousError.set('');
    if(!error && this.connected() && status?.versions?.devServer === this.selected()) {
      this.pending.set(false);this.updated.emit(this.selected());
    }
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
