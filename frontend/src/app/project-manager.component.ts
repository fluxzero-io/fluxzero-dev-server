import {Component, ElementRef, ViewChild, computed, inject, input, signal} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';
import {Environment} from './models';
import {sendCommand} from './dom-handlers';

type Folder = {path:string; parent:string; folders:{name:string;path:string}[]; truncated:boolean; project:boolean; ancestors:{name:string;path:string}[]};
@Component({selector:'dev-project-manager',standalone:true,template:`
  <dialog #dialog aria-labelledby="manage-projects-title" (pointerdown)="backdropPressed = isBackdrop($event)" (click)="dismissBackdrop($event)" (cancel)="cancel($event)" (close)="restoreFocus()" (keydown)="$event.stopPropagation()">
    <header><h2 id="manage-projects-title">{{mode() === 'list' ? 'Manage projects' : mode() === 'new' ? 'New project' : 'Open other project'}}</h2>
      <button class="icon-button" aria-label="Close project manager" [disabled]="busy()" (click)="dialog.close()"><i class="bi bi-x-lg" aria-hidden="true"></i></button></header>
    @if(error()) {<p class="error" role="alert">{{error()}}</p>}
    @if(mode() === 'list') {
      <div class="toolbar"><button class="secondary-button" [disabled]="busy()" (click)="begin('open')">Open other…</button><button class="primary-button" [disabled]="busy()" (click)="begin('new')">New project…</button></div>

      @if(stopping();as target) {<div class="stop-confirm" role="group" aria-label="Confirm stop"><p>Stop {{target.projectName}}?</p><p class="hint">Its app and local services will stop. You can start it again here.</p><div class="dialog-actions"><button class="secondary-button dialog-cancel" [disabled]="busy()" (click)="stopping.set(null)">Cancel</button><button class="primary-button" [disabled]="busy()" (click)="stopProject(target)">{{busy() ? 'Stopping…' : 'Stop project'}}</button></div></div>}
      <div class="project-list">
      @for(project of sortedProjects();track project.id) {
        <div class="project-row">
          <div class="project-info">
            @if(editing() === project.id) {
              <form (submit)="$event.preventDefault(); rename(project, name.value)"><input #name aria-label="Project name" [value]="project.projectName" maxlength="100"><button class="secondary-button" [disabled]="busy()">Save</button><button type="button" class="secondary-button" (click)="editing.set('')">Cancel</button></form>
            } @else {<div class="manager-project-name"><strong>{{project.projectName}}</strong><button class="icon-button" title="Rename project" aria-label="Rename project" [disabled]="busy()" (click)="editing.set(project.id)"><i class="bi bi-pencil" aria-hidden="true"></i></button></div>}
            <span class="path" [title]="project.projectDirectory">{{project.projectDirectory}}</span><span class="project-status" [class.running]="project.status === 'running'"><span class="status-dot" aria-hidden="true"></span>{{!project.directoryExists ? 'Folder not found' : project.status === 'running' ? 'Running' : 'Stopped'}}</span>
          </div>
          <div class="row-actions" role="group" [attr.aria-label]="'Actions for ' + project.projectName">
            @if(project.projectDirectory !== currentDirectory()) {<button class="icon-button project-action manager-open-action" [title]="switchingProject() === project.id ? 'Starting project…' : 'Switch to project'" aria-label="Switch to project" [disabled]="busy() || !project.directoryExists" (click)="openProject(project)">@if(switchingProject() === project.id) {<i class="bi bi-arrow-repeat starting-icon" aria-hidden="true"></i>} @else {<svg class="switch-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.3" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 10a4 4 0 0 1 4-4h14m-4-4 4 4-4 4M21 14a4 4 0 0 1-4 4H3m4-4-4 4 4 4"/></svg>}</button>}

            @if(project.status === 'running') {

              <button class="icon-button project-action manager-stop-action" [disabled]="busy()" [title]="project.projectDirectory === currentDirectory() ? 'Stop via Workspace' : 'Stop project'" [attr.aria-label]="project.projectDirectory === currentDirectory() ? 'Stop via Workspace' : 'Stop project'" (click)="requestStop(project)"><i class="bi bi-stop-fill" aria-hidden="true"></i></button>
            } @else {<button class="icon-button project-action manager-start-action" [disabled]="busy() || !project.directoryExists" [title]="busyProject() === project.id ? 'Starting…' : 'Start project'" [attr.aria-label]="busyProject() === project.id ? 'Starting project' : 'Start project'" (click)="startProject(project)"><i [class]="busyProject() === project.id ? 'bi bi-arrow-repeat starting-icon' : 'bi bi-play-fill'" aria-hidden="true"></i></button>}
            <button class="icon-button project-action manager-remove-action" [title]="project.status === 'stopped' ? 'Remove from list — keeps files' : 'Stop the project before removing it from the list'" aria-label="Remove from list" [disabled]="busy() || project.status !== 'stopped'" (click)="remove(project)"><i class="bi bi-trash3" aria-hidden="true"></i></button>
          </div>
        </div>
      } @empty {<p>No projects yet.</p>}
      </div>
      <p class="list-footnote"><i class="bi bi-info-circle" aria-hidden="true"></i> Removing a project from this list keeps its files.</p>
      @if(missing().length) {<button class="secondary-button cleanup" [disabled]="busy()" (click)="removeMissing()">Remove missing folders from list</button>}
    } @else {
      @if(mode() === 'new') {<label>Project name<input #newName placeholder="my-project" [value]="projectName()" (input)="projectName.set(newName.value)" [disabled]="busy()" maxlength="64" autocomplete="off"></label>}
      <label>{{mode() === 'new' ? 'Location' : 'Project folder'}}<div class="path-input"><input #path aria-label="Folder path" [value]="folderPath()" (input)="folderPath.set(path.value)" [disabled]="busy()" (keydown.enter)="$event.preventDefault(); browse(folderPath())"><button class="secondary-button" [disabled]="busy() || loading()" (click)="browse(folderPath())">Browse</button></div></label>
      @if(folder();as current) {
        <nav class="folder-heading breadcrumbs" aria-label="Folder path navigation">
          @for(crumb of crumbs();track crumb.path) {
            @if(!$first) {<i class="bi bi-chevron-right" aria-hidden="true"></i>}
            @if(crumb.path === '…') {<button class="crumb" title="Show all parent folders" aria-label="Show all parent folders" (click)="expandedPath.set(true)">…</button>}
            @else {<button class="crumb" [title]="crumb.path" [attr.aria-current]="$last ? 'location' : null" [disabled]="loading() || busy() || $last" (click)="browse(crumb.path)">{{crumb.name}}</button>}
          }
        </nav>
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
  .hint,.path {color:var(--dashboard-muted);font-size:12px;} .path {display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;margin-top:2px;font-size:11px;line-height:18px;} .error {color:var(--dashboard-danger-text);}
  .manager-project-name {display:flex;align-items:center;gap:4px;min-height:26px;} .manager-project-name strong {overflow:hidden;text-overflow:ellipsis;white-space:nowrap;} .manager-project-name .icon-button {flex:none;width:26px;height:26px;} .manager-project-name .icon-button i {font-size:12px;color:var(--dashboard-muted);} .manager-project-name .icon-button:hover i {color:var(--dashboard-active);} .switch-icon {display:block;width:17px;height:17px;flex:none;} .starting-icon {display:inline-block;animation:project-spin 1s linear infinite;} @keyframes project-spin {to {transform:rotate(360deg);}}
  .project-list {max-height:50dvh;overflow:auto;} .project-row {display:flex;gap:20px;align-items:center;padding:16px 0;border-bottom:1px solid var(--dashboard-border);} .project-row:last-child {border-bottom:0;} .project-info {flex:1;min-width:0;} strong {font-size:14px;} .row-actions {flex-shrink:0;gap:4px;padding:4px;} .row-actions .project-action {width:32px;height:32px;border-radius:6px;background:var(--dashboard-panel-soft);} .project-action i {font-size:15px;} .project-action.manager-open-action {color:var(--dashboard-active);} .project-action.manager-start-action {color:var(--dashboard-active);} .project-action.manager-stop-action {color:var(--dashboard-active);} .project-action.manager-remove-action {color:var(--dashboard-muted);} .row-actions .project-action:is(:hover,:focus-visible):not(:disabled) {background:var(--dashboard-active-soft);color:var(--dashboard-active);} .row-actions .manager-remove-action:hover:not(:disabled) {background:var(--dashboard-danger-bg);color:var(--dashboard-danger-text);} .row-actions .project-action:disabled {opacity:.3;} .project-status {display:flex;align-items:center;gap:5px;margin-top:4px;font-size:11px;color:var(--dashboard-muted);} .project-status.running {color:var(--dashboard-success-text);} .status-dot {width:5px;height:5px;border-radius:50%;background:currentColor;} .list-footnote {display:flex;align-items:center;gap:6px;color:var(--dashboard-muted);font-size:11px;margin:16px 0 0;padding-top:12px;border-top:1px solid var(--dashboard-border);} .cleanup {margin-top:14px;}
  label {display:block;margin:16px 0;font-size:14px;} input {display:block;box-sizing:border-box;width:100%;min-width:0;margin-top:6px;padding:9px 10px;border:1px solid var(--dashboard-border);border-radius:6px;background:var(--dashboard-surface);color:var(--dashboard-text);font:inherit;} .path-input input {margin:0;} .path-input {margin-top:6px;} .folder-heading {margin:12px 0;}
  .breadcrumbs {gap:4px;flex-wrap:wrap;min-width:0;} .breadcrumbs > i {font-size:10px;color:var(--dashboard-muted);} .crumb {border:0;border-radius:5px;background:transparent;color:var(--dashboard-muted);padding:6px;max-width:180px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;} .crumb:hover:not(:disabled) {background:var(--dashboard-active-soft);color:var(--dashboard-active);} .crumb[aria-current] {color:var(--dashboard-text);opacity:1;} .stop-confirm {padding:14px;margin:12px 0;border:1px solid var(--dashboard-border);border-radius:8px;}
  .folders {border:1px solid var(--dashboard-border);border-radius:8px;max-height:240px;overflow:auto;padding:4px;} .folders button {display:flex;align-items:center;gap:10px;width:100%;border:0;border-radius:5px;padding:10px;background:transparent;color:var(--dashboard-text);text-align:left;} .folders button:hover {background:var(--dashboard-active-soft);} .folders button i:last-child {margin-left:auto;} form {display:flex;gap:6px;flex-wrap:wrap;} form input {width:100%;}
  @media(max-width:600px) {dialog {padding:18px;} .project-row {align-items:center;gap:10px;flex-wrap:nowrap;} .project-info {min-width:0;} .row-actions {gap:2px;padding:3px;} .row-actions .project-action {width:30px;height:34px;} }
`})
export class ProjectManagerComponent {
  environments=input<Environment[]>([]); currentDirectory=input('');
  sortedProjects=computed(()=>{
    const current=this.currentDirectory();
    const rank=(project:Environment)=>project.projectDirectory === current ? 0 : project.status === 'running' ? 1 : 2;
    return [...this.environments()].sort((a,b)=>rank(a)-rank(b) || a.projectName.localeCompare(b.projectName,undefined,{numeric:true,sensitivity:'base'}) || a.id.localeCompare(b.id));
  });
  expandedPath=signal(false); stopping=signal<Environment|null>(null);
  @ViewChild('dialog') dialog!:ElementRef<HTMLDialogElement>;
  private element=inject<ElementRef<HTMLElement>>(ElementRef); private http=inject(HttpClient);
  private trigger?:HTMLElement;
  backdropPressed=false;
  mode=signal<'list'|'open'|'new'>('list'); busy=signal(false); loading=signal(false); error=signal(''); editing=signal('');
  folder=signal<Folder|undefined>(undefined); folderPath=signal(''); projectName=signal('');
  busyProject=signal(''); switchingProject=signal('');
  crumbs() {const all=this.folder()?.ancestors || [];return !this.expandedPath() && all.length>4 ? [all[0],{name:'…',path:'…'},...all.slice(-2)] : all;}
  missing() {return this.environments().filter(p=>!p.directoryExists && p.status==='stopped');}
  show(trigger:HTMLElement) {this.trigger=trigger;this.mode.set('list');this.error.set('');this.editing.set('');this.stopping.set(null);this.dialog.nativeElement.showModal();}
  isBackdrop(event:MouseEvent) {
    const dialog=this.dialog.nativeElement;
    if(event.target!==dialog)return false;
    const rect=dialog.getBoundingClientRect();
    return event.clientX<rect.left || event.clientX>rect.right || event.clientY<rect.top || event.clientY>rect.bottom;
  }
  dismissBackdrop(event:MouseEvent) {
    const dismiss=this.backdropPressed && this.isBackdrop(event);
    this.backdropPressed=false;
    if(dismiss && !this.busy() && !this.stopping())this.dialog.nativeElement.close();
  }
  restoreFocus() {this.trigger?.focus();}
  cancel(event:Event) {if(this.busy()) event.preventDefault();}
  async begin(mode:'open'|'new') {this.mode.set(mode);this.error.set('');await this.browse(this.folderPath());}
  private post<T>(operation:string, data:unknown) {return firstValueFrom(this.http.post<T>('projects/'+operation,data,{headers:{'X-Fluxzero-Console':'1'},timeout:190000}));}
  async browse(path:string) {if(this.loading())return;this.loading.set(true);this.error.set('');try {const f=await this.post<Folder>('folders',{path});this.folder.set(f);this.folderPath.set(f.path);this.expandedPath.set(false);}catch(e:any){this.error.set(e?.error?.error||'Could not read this folder.');}finally{this.loading.set(false);}}
  private async act(action:()=>Promise<unknown>) {if(this.busy())return;this.busy.set(true);this.error.set('');try{await action();}catch(e:any){this.error.set(e?.error?.error||e?.message||'Could not complete this action.');}finally{this.busy.set(false);}}
  async rename(project:Environment,name:string) {await this.act(async()=>{await sendCommand(this.element.nativeElement,'renameProject',{id:project.id,name});this.editing.set('');});}
  async remove(project:Environment) {await this.act(async()=>{await this.post(project.id+'/forget',null);await sendCommand(this.element.nativeElement,'refreshProjects');});}
  async removeMissing() {await this.act(async()=>{for(const p of this.missing())await this.post(p.id+'/forget',null);await sendCommand(this.element.nativeElement,'refreshProjects');});}
  requestStop(project:Environment) {
    if(project.projectDirectory === this.currentDirectory()) {sendCommand(this.element.nativeElement,'showWorkspace');this.dialog.nativeElement.close();}
    else this.stopping.set(project);
  }
  async stopProject(project:Environment) {await this.act(async()=>{await this.post(project.id+'/stop',null);this.stopping.set(null);await sendCommand(this.element.nativeElement,'refreshProjects');});}
  async startProject(project:Environment) {this.busyProject.set(project.id);await this.act(async()=>{await sendCommand(this.element.nativeElement,'startProjectInBackground',project.id);});this.busyProject.set('');}
  async openProject(project:Environment) {if(this.busy() || !project.directoryExists)return; if(project.status!=='running')this.switchingProject.set(project.id); await this.act(async()=>{if(project.status==='running')await sendCommand(this.element.nativeElement,'openEnvironment',project);else await sendCommand(this.element.nativeElement,'startEnvironment',project.id);this.dialog.nativeElement.close();});this.switchingProject.set('');}
  async submit() {await this.act(async()=>{const p=await this.post<Environment>(this.mode()==='new'?'create':'open',{path:this.folderPath(),name:this.projectName()});await sendCommand(this.element.nativeElement,'refreshProjects');await sendCommand(this.element.nativeElement,'startEnvironment',p.id);this.dialog.nativeElement.close();});}
}
