import {ChangeDetectorRef, Component, ElementRef, inject, input, signal, ViewChild} from '@angular/core';
import {Environment} from './models';
import {sendCommand} from './dom-handlers';

@Component({selector: 'dev-environment-name', standalone: true, template: `
  <button #trigger class="icon-button" type="button" aria-label="Rename dev server" title="Rename dev server" (click)="editName()">
    <i class="bi bi-pencil" aria-hidden="true"></i>
  </button>
  <dialog #nameDialog class="rename-dialog" aria-labelledby="server-name-title" (close)="restoreFocus()">
    <form (submit)="saveName($event)">
      <h2 id="server-name-title">Rename dev server</h2>
      <label for="server-name">Name</label>
      <input #nameInput id="server-name" type="text" maxlength="100" required autocomplete="off"
        [value]="draft()" (input)="draft.set(nameInput.value)" [disabled]="saving()">
      <button class="folder-name" type="button" [disabled]="saving()" (click)="useFolderName()">Use folder name</button>
      @if(error()) {<p role="alert">{{error()}}</p>}
      <div class="dialog-actions">
        <button class="primary-button" type="submit" [disabled]="saving() || !draft().trim()">{{saving() ? 'Saving…' : 'Save'}}</button>
        <button class="secondary-button" type="button" [disabled]="saving()" (click)="nameDialog.close()">Cancel</button>
      </div>
    </form>
  </dialog>`, styles: `
  :host {display:inline-flex;flex-shrink:0;}
  .rename-dialog {width:420px;max-width:calc(100vw - 24px);padding:24px;border:1px solid var(--dashboard-border);border-radius:8px;background:var(--dashboard-popover-bg);color:var(--dashboard-text);box-shadow:var(--dashboard-popover-shadow);}
  .rename-dialog::backdrop {background:rgba(0,0,0,.4);}
  h2 {margin-bottom:24px;}
  label {display:block;margin-bottom:8px;}
  input {width:100%;min-width:0;padding:10px;border:1px solid var(--dashboard-border);border-radius:6px;background:var(--dashboard-control-bg);color:var(--dashboard-text);}
  .folder-name {display:block;margin:8px 0 24px;padding:0;border:0;background:transparent;color:var(--dashboard-active);font-size:12px;}
  [role=alert] {margin-bottom:16px;color:var(--dashboard-danger-text);}
`})
export class EnvironmentNameComponent {
  readonly elementRef = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly changeDetector = inject(ChangeDetectorRef);
  @ViewChild('trigger') trigger?: ElementRef<HTMLButtonElement>;
  @ViewChild('nameDialog') nameDialog?: ElementRef<HTMLDialogElement>;
  @ViewChild('nameInput') nameInput?: ElementRef<HTMLInputElement>;
  readonly id = input.required<string>();
  readonly name = input.required<string>();
  readonly directory = input.required<string>();
  readonly draft = signal('');
  readonly saving = signal(false);
  readonly error = signal('');
  editName() {
    this.draft.set(this.name());
    this.error.set('');
    this.changeDetector.detectChanges();
    this.nameDialog?.nativeElement.showModal();
    this.nameInput?.nativeElement.select();
  }
  useFolderName() {
    this.draft.set(this.directory().replace(/[\\/]+$/, '').split(/[\\/]/).pop() || this.name());
    this.nameInput?.nativeElement.focus();
  }
  async saveName(event: Event) {
    event.preventDefault();
    if (this.saving() || !this.id() || !this.draft().trim()) return;
    this.saving.set(true);
    this.error.set('');
    try {
      await sendCommand<Promise<Environment>>(this.elementRef.nativeElement, 'renameEnvironment', {id: this.id(), name: this.draft().trim()});
      this.nameDialog?.nativeElement.close();
    } catch (error: any) { this.error.set(error?.error?.error || 'Unable to rename the dev server.'); }
    finally { this.saving.set(false); }
  }
  restoreFocus() { this.trigger?.nativeElement.focus(); }
}
