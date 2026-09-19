import {Component, ElementRef, ViewChild, inject, input, signal} from '@angular/core';
import {sendCommand} from './dom-handlers';

@Component({selector: 'dev-project-rename', standalone: true, template: `
  <button type="button" class="icon-button rename-project-button" aria-label="Rename project" title="Rename project" [disabled]="!id()" (click)="open()"><i class="bi bi-pencil" aria-hidden="true"></i></button>
  <dialog #dialog class="project-rename-dialog" (cancel)="saving() && $event.preventDefault()" aria-labelledby="rename-project-title" aria-describedby="rename-project-description">
    <form (submit)="save($event)">
      <h2 id="rename-project-title">Rename project</h2>
      <p id="rename-project-description">Choose a name for this project in your Devboard. The folder stays the same.</p>
      <label for="project-name">Project name</label>
      <input #nameInput id="project-name" class="project-name-input" maxlength="100" autocomplete="off" autofocus [value]="draft()" (input)="draft.set(nameInput.value)" [disabled]="saving()">
      <small>Leave empty to use the folder name.</small>
      @if(error()) {<p role="alert">{{error()}}</p>}
      <div class="dialog-actions">
        <button type="button" class="secondary-button dialog-cancel" (click)="dialog.close()" [disabled]="saving()">Cancel</button>
        <button type="submit" class="primary-button" [disabled]="saving()">{{saving() ? 'Saving…' : 'Save'}}</button>
      </div>
    </form>
  </dialog>`})
export class ProjectRenameComponent {
  id = input(''); name = input(''); draft = signal(''); saving = signal(false); error = signal('');
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  @ViewChild('dialog') dialog!: ElementRef<HTMLDialogElement>;
  @ViewChild('nameInput') nameInput!: ElementRef<HTMLInputElement>;
  open() {
    if (this.saving()) return;
    this.draft.set(this.name()); this.error.set('');
    this.nameInput.nativeElement.value = this.name();
    this.dialog.nativeElement.showModal(); this.nameInput.nativeElement.select();
  }
  async save(event: Event) {
    event.preventDefault(); if (this.saving()) return;
    this.saving.set(true); this.error.set('');
    try {
      await sendCommand<Promise<void>>(this.host.nativeElement, 'renameProject', {id: this.id(), name: this.draft().trim()});
      this.dialog.nativeElement.close();
    } catch (error: any) { this.error.set(error?.error?.error || 'Unable to rename the project. Try again.'); }
    finally { this.saving.set(false); }
  }
}
