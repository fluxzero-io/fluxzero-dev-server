import {Component, ElementRef, ViewChild, inject, input, signal} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';
import {Environment} from './models';
import {sendCommand} from './dom-handlers';

type Folder = {path:string; parent:string; folders:{name:string;path:string}[]; truncated:boolean; project:boolean};
@Component({selector:'dev-project-manager',standalone:true,template:`
  <dialog #dialog aria-labelledby="manage-projects-title" (cancel)="cancel($event)" (close)="restoreFocus()" (keydown)="$event.stopPropagation()">
    <header><h2 id="manage-projects-title">{{mode() === 'list' ? 'Manage projects' : mode() === 'new' ? 'New project' : 'Open existing project'}}</h2>
      <button class="icon-button" aria-label="Close project manager" [disabled]="busy()" (click)="dialog.close()"><i class="bi bi-x-lg" aria-hidden="true"></i></button></header>
    @if(error()) {<p class="error" role="alert">{{error()}}</p>}
    @if(mode() === 'list') {
      <div class="toolbar"><button class="secondary-button" [disabled]="busy()" (click)="begin('open')">Open existing…</button><button class="primary-button" [disabled]="busy()" (click)="begin('new')">New project…</button></div>
      <p class="hint">Removing a project from this list keeps all its files.</p>
      <div class="project-list">
      @for(project of environments();track project.id) {
        <div class="project-row">
          <div class="project-info">
            @if(editing() === project.id) {
              <form (submit)="$event.preventDefault(); rename(project, name.value)"><input #name aria-label="Project name" [value]="project.projectName" maxlength="100"><button class="secondary-button" [disabled]="busy()">Save</button><button type="button" class="secondary-button" (click)="editing.set('')">Cancel</button></form>
            } @else {<strong>{{project.projectName}}</strong>}
            <span class="path">{{project.projectDirectory}}</span><span class="hint">{{!project.directoryExists ? 'Folder not found' : project.status === 'running' ? 'Running' : 'Stopped'}}</span>
          </div>
          <div class="row-actions">
            <button class="icon-button" title="Rename project" aria-label="Rename project" [disabled]="busy()" (click)="editing.set(project.id)"><i class="bi bi-pencil" aria-hidden="true"></i></button>
            <button class="secondary-button" [disabled]="busy() || !project.directoryExists" (click)="openProject(project)">Open</button>
            <button class="icon-button" title="Remove from list (stop the project first if running)" aria-label="Remove from list" [disabled]="busy() || project.status !== 'stopped'" (click)="remove(project)"><i class="bi bi-trash" aria-hidden="true"></i></button>
          </div>
        </div>
      } @empty {<p>No projects yet.</p>}
      </div>
      @if(missing().length) {<button class="secondary-button cleanup" [disabled]="busy()" (click)="removeMissing()">Remove missing folders from list</button>}
    } @else {
      @if(mode() === 'new') {<label>Project name<input #newName placeholder="my-project" [value]="projectName()" (input)="projectName.set(newName.value)" [disabled]="busy()" maxlength="64" autocomplete="off"></label>}
      <label>{{mode() === 'new' ? 'Location' : 'Project folder'}}<div class="path-input"><input #path aria-label="Folder path" [value]="folderPath()" (input)="folderPath.set(path.value)" [disabled]="busy()" (keydown.enter)="$event.preventDefault(); browse(folderPath())"><button class="secondary-button" [disabled]="busy() || loading()" (click)="browse(folderPath())">Browse</button></div></label>
      @if(folder();as current) {
        <div class="folder-heading"><button class="secondary-button" [disabled]="!current.parent || loading() || busy()" (click)="browse(current.parent)"><i class="bi bi-arrow-up" aria-hidden="true"></i> Up</button><span class="path">{{current.path}}</span></div>
        <div class="folders" aria-label="Folders" [attr.aria-busy]="loading()">
          @for(child of current.folders;track child.path) {<button [disabled]="busy() || loading()" (click)="browse(child.path)"><i class="bi bi-folder" aria-hidden="true"></i>{{child.name}}<i class="bi bi-chevron-right" aria-hidden="true"></i></button>}
          @if(!current.folders.length) {<p class="hint">No subfolders</p>}
        </div>
        @if(current.truncated) {<p class="hint">Showing the first 200 folders. Enter a path to open another folder.</p>}
      }
      <p class="hint">{{mode() === 'new' ? 'A new folder will be created here. Existing folders are never overwritten.' : 'Choose the folder containing your project.'}}</p>
      <div class="dialog-actions"><button class="secondary-button dialog-cancel" [disabled]="busy()" (click)="mode.set('list');error.set('')">Cancel</button><button class="primary-button" [disabled]="busy() || loading() || !folderPath() || (mode() === 'new' && !projectName())" (click)="submit()">{{busy() ? 'Preparing…' : mode() === 'new' ? 'Create & open' : 'Open project'}}</button></div>
    }
  </dialog>
`,styles:`
  dialog {width:720px;max-width:calc(100vw - 24px);max-height:calc(100dvh - 32px);box-sizing:border-box;padding:24px;border:1px solid var(--dashboard-border);border-radius:14px;background:var(--dashboard-surface);color:var(--dashboard-text);overflow:auto;}
  dialog::backdrop {background:#10233d66;} header,.toolbar,.row-actions,.folder-heading,.path-input {display:flex;align-items:center;gap:10px;} header {justify-content:space-between;margin-bottom:20px;} h2 {margin:0;font-size:22px;} .toolbar {margin-bottom:12px;flex-wrap:wrap;}
  .hint,.path {color:var(--dashboard-muted);font-size:12px;} .path {overflow-wrap:anywhere;display:block;} .error {color:var(--dashboard-danger-text);}
  .project-list {max-height:50dvh;overflow:auto;} .project-row {display:flex;gap:12px;align-items:center;padding:14px 0;border-top:1px solid var(--dashboard-border);} .project-info {flex:1;min-width:0;} strong {font-size:14px;} .row-actions {flex-shrink:0;} .cleanup {margin-top:14px;}
  label {display:block;margin:16px 0;font-size:14px;} input {display:block;box-sizing:border-box;width:100%;min-width:0;margin-top:6px;padding:9px 10px;border:1px solid var(--dashboard-border);border-radius:6px;background:var(--dashboard-surface);color:var(--dashboard-text);font:inherit;} .path-input input {margin:0;} .path-input {margin-top:6px;} .folder-heading {margin:12px 0;}
  .folders {border:1px solid var(--dashboard-border);border-radius:8px;max-height:240px;overflow:auto;padding:4px;} .folders button {display:flex;align-items:center;gap:10px;width:100%;border:0;border-radius:5px;padding:10px;background:transparent;color:var(--dashboard-text);text-align:left;} .folders button:hover {background:var(--dashboard-active-soft);} .folders button i:last-child {margin-left:auto;} form {display:flex;gap:6px;flex-wrap:wrap;} form input {width:100%;}
  @media(max-width:600px) {dialog {padding:18px;} .project-row {align-items:flex-start;flex-wrap:wrap;} .project-info {flex-basis:100%;} .row-actions {margin-left:auto;} }
`})
export class ProjectManagerComponent {
  environments=input<Environment[]>([]);
  @ViewChild('dialog') dialog!:ElementRef<HTMLDialogElement>;
  private element=inject<ElementRef<HTMLElement>>(ElementRef); private http=inject(HttpClient);
  private trigger?:HTMLElement;
  mode=signal<'list'|'open'|'new'>('list'); busy=signal(false); loading=signal(false); error=signal(''); editing=signal('');
  folder=signal<Folder|undefined>(undefined); folderPath=signal(''); projectName=signal('');
  missing() {return this.environments().filter(p=>!p.directoryExists && p.status==='stopped');}
  show(trigger:HTMLElement) {this.trigger=trigger;this.mode.set('list');this.error.set('');this.editing.set('');this.dialog.nativeElement.showModal();}
  restoreFocus() {this.trigger?.focus();}
  cancel(event:Event) {if(this.busy()) event.preventDefault();}
  async begin(mode:'open'|'new') {this.mode.set(mode);this.error.set('');await this.browse(this.folderPath());}
  private post<T>(operation:string, data:unknown) {return firstValueFrom(this.http.post<T>('projects/'+operation,data,{headers:{'X-Fluxzero-Console':'1'},timeout:190000}));}
  async browse(path:string) {if(this.loading())return;this.loading.set(true);this.error.set('');try {const f=await this.post<Folder>('folders',{path});this.folder.set(f);this.folderPath.set(f.path);}catch(e:any){this.error.set(e?.error?.error||'Could not read this folder.');}finally{this.loading.set(false);}}
  private async act(action:()=>Promise<unknown>) {if(this.busy())return;this.busy.set(true);this.error.set('');try{await action();}catch(e:any){this.error.set(e?.error?.error||e?.message||'Could not complete this action.');}finally{this.busy.set(false);}}
  async rename(project:Environment,name:string) {await this.act(async()=>{await sendCommand(this.element.nativeElement,'renameProject',{id:project.id,name});this.editing.set('');});}
  async remove(project:Environment) {await this.act(async()=>{await this.post(project.id+'/forget',null);await sendCommand(this.element.nativeElement,'refreshProjects');});}
  async removeMissing() {await this.act(async()=>{for(const p of this.missing())await this.post(p.id+'/forget',null);await sendCommand(this.element.nativeElement,'refreshProjects');});}
  async openProject(project:Environment) {await this.act(async()=>{if(project.status==='running')await sendCommand(this.element.nativeElement,'openEnvironment',project);else await sendCommand(this.element.nativeElement,'startEnvironment',project.id);this.dialog.nativeElement.close();});}
  async submit() {await this.act(async()=>{const p=await this.post<Environment>(this.mode()==='new'?'create':'open',{path:this.folderPath(),name:this.projectName()});await sendCommand(this.element.nativeElement,'refreshProjects');await sendCommand(this.element.nativeElement,'startEnvironment',p.id);this.dialog.nativeElement.close();});}
}
