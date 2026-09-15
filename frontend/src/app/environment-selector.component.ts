import {ChangeDetectorRef, Component, computed, ElementRef, HostListener, inject, input, signal, ViewChild} from '@angular/core';
import {Environment, environmentConsoleUrl} from './models';
import {ProjectPathComponent} from './project-path.component';
import {sendCommand} from './dom-handlers';

@Component({selector: 'dev-environment-selector', standalone: true, imports: [ProjectPathComponent], template: `
  <div class="dashboard-section-label">Current dev server</div>
  <button #trigger class="server-picker-button" type="button" aria-label="Choose dev server" aria-haspopup="dialog"
    aria-controls="dev-server-picker" [attr.aria-expanded]="open()" [title]="currentName()" (click)="toggle()">
    <i class="bi bi-collection" aria-hidden="true"></i><span>{{currentName()}}</span><i class="bi bi-chevron-down" aria-hidden="true"></i>
  </button>
  @if(open()) {
    <div id="dev-server-picker" class="server-menu" role="dialog" aria-label="Dev servers">
      <div class="search"><i class="bi bi-search" aria-hidden="true"></i>
        <input #search type="search" placeholder="Search by name or folder" aria-label="Search dev servers"
          [value]="filter()" (input)="filter.set(search.value)"></div>
      <div class="server-options">
        @for(environment of filtered(); track environment.id) {
          <div class="server-option" [class.selected]="environment.id === current()?.id">
            @if(url(environment); as href) {
              <a class="server-option-name" [href]="href" [attr.aria-current]="environment.id === current()?.id ? 'true' : null"
                (click)="select(environment, $event)">{{environment.projectName}}
                @if(environment.id === current()?.id) {<i class="bi bi-check2" aria-hidden="true"></i>}
              </a>
            } @else if(environment.status === 'stopped' && environment.directoryExists) {
              <button class="server-option-name inactive-server" type="button" (click)="offerStart(environment)">{{environment.projectName}}</button>
            } @else {<span class="server-option-name">{{environment.projectName}}</span>}
            <span class="server-state" [class.running]="environment.status === 'running'">{{environment.detail || environment.status}}</span>
            <div class="server-path"><dev-project-path [path]="environment.projectDirectory" [id]="environment.id" [exists]="environment.directoryExists"/></div>
            @if(environment.status === 'stopped') {
              <button class="icon-button remove-project" type="button" [attr.aria-label]="'Remove ' + environment.projectName + ' from overview'"
                title="Remove from overview; keep project files" (click)="forget(environment)"><i class="bi bi-trash" aria-hidden="true"></i></button>
            }
          </div>
        } @empty {<p class="server-empty">No matching dev servers</p>}
      </div>
      <button class="rename-server" type="button" [disabled]="!current()" (click)="editName()">
        <span>Rename dev server</span><i class="bi bi-pencil" aria-hidden="true"></i>
      </button>
    </div>
  }
  <dialog #startDialog class="rename-dialog" aria-labelledby="server-start-title" (close)="restoreFocus()" (cancel)="cancelStart($event)">
    <h2 id="server-start-title">Start dev server?</h2>
    <p>{{selectedToStart()?.projectName}} is inactive. Would you like to start it?</p>
    @if(startError()) {<p role="alert">{{startError()}}</p>}
    <div class="dialog-actions">
      <button class="primary-button" type="button" [disabled]="starting()" (click)="startServer()">
        @if(starting()) {<span class="spinner-border spinner-border-sm" aria-hidden="true"></span>}{{starting() ? 'Starting…' : 'Start dev server'}}
      </button>
      <button class="secondary-button" type="button" [disabled]="starting()" (click)="startDialog.close()">Cancel</button>
    </div>
  </dialog>
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
  :host {display:block;position:relative;margin-bottom:20px;min-width:0;}
  .server-picker-button {display:grid;grid-template-columns:16px minmax(0,1fr) 14px;align-items:center;gap:5px;width:100%;min-height:38px;
    padding:0 8px;border:1px solid var(--dashboard-border);border-radius:6px;background:var(--dashboard-surface);color:var(--dashboard-text);text-align:left;}
  .server-picker-button span {overflow:hidden;font-size:15px;font-weight:700;text-overflow:ellipsis;white-space:nowrap;}
  .server-picker-button i {color:var(--dashboard-control-icon);font-size:14px;}
  .server-picker-button:hover {border-color:var(--dashboard-focus-border);}
  .server-menu {position:absolute;top:calc(100% + 6px);left:0;z-index:30;width:380px;max-width:calc(100vw - 44px);max-height:calc(100dvh - 230px);
    display:flex;flex-direction:column;padding:6px;border:1px solid var(--dashboard-border);border-radius:8px;background:var(--dashboard-popover-bg);box-shadow:var(--dashboard-popover-shadow);}
  .search {flex:none;width:100%;margin:0 0 6px;padding:8px 10px;}
  .server-options {overflow-y:auto;overscroll-behavior:contain;min-height:0;}
  .server-option {display:grid;grid-template-columns:minmax(0,1fr) auto;gap:4px 8px;padding:10px;border-radius:5px;}
  .server-option + .server-option {border-top:1px solid var(--dashboard-border);}
  .server-option.selected {background:var(--dashboard-active-soft);}
  .server-option-name {grid-column:1;display:flex;align-items:center;justify-content:space-between;gap:8px;color:var(--dashboard-text);font-weight:600;overflow-wrap:anywhere;min-width:0;}
  .inactive-server {border:0;padding:0;background:transparent;text-align:left;font:inherit;font-weight:600;}
  .inactive-server:hover {text-decoration:underline;}
  .server-option-name i {color:var(--dashboard-active);}
  .server-state {grid-column:2;align-self:center;font-size:12px;color:var(--dashboard-muted);}
  .server-state.running {color:var(--dashboard-success-text);}
  .server-path {grid-column:1;min-width:0;font-size:12px;color:var(--dashboard-muted);overflow-wrap:anywhere;}
  .remove-project {grid-column:2;justify-self:end;}
  .rename-server {flex:none;display:flex;justify-content:space-between;gap:12px;align-items:center;min-height:40px;margin-top:6px;padding:8px 10px;
    border:0;border-top:1px solid var(--dashboard-border);background:transparent;color:var(--dashboard-text);text-align:left;}
  .rename-server:hover {background:var(--dashboard-active-soft);}
  .server-empty {padding:16px 10px;}
  .rename-dialog {width:420px;max-width:calc(100vw - 24px);padding:24px;border:1px solid var(--dashboard-border);border-radius:8px;background:var(--dashboard-popover-bg);color:var(--dashboard-text);box-shadow:var(--dashboard-popover-shadow);}
  .rename-dialog::backdrop {background:rgba(0,0,0,.4);}
  .rename-dialog > p {margin-bottom:24px;}
  .rename-dialog h2 {margin-bottom:24px;}
  .rename-dialog label {display:block;margin-bottom:8px;}
  .rename-dialog input {width:100%;min-width:0;padding:10px;border:1px solid var(--dashboard-border);border-radius:6px;background:var(--dashboard-control-bg);color:var(--dashboard-text);}
  .folder-name {display:block;margin:8px 0 24px;padding:0;border:0;background:transparent;color:var(--dashboard-active);font-size:12px;}
  .rename-dialog [role=alert] {margin-bottom:16px;color:var(--dashboard-danger-text);}
`})
export class EnvironmentSelectorComponent {
  readonly elementRef = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly changeDetector = inject(ChangeDetectorRef);
  @ViewChild('trigger') trigger?: ElementRef<HTMLButtonElement>;
  @ViewChild('search') search?: ElementRef<HTMLInputElement>;
  @ViewChild('startDialog') startDialog?: ElementRef<HTMLDialogElement>;
  @ViewChild('nameDialog') nameDialog?: ElementRef<HTMLDialogElement>;
  @ViewChild('nameInput') nameInput?: ElementRef<HTMLInputElement>;
  readonly environments = input<Environment[]>([]);
  readonly current = input<Environment>();
  readonly currentName = input('Select dev server');
  readonly open = signal(false);
  readonly filter = signal('');
  readonly draft = signal('');
  readonly saving = signal(false);
  readonly error = signal('');
  readonly selectedToStart = signal<Environment | null>(null);
  readonly starting = signal(false);
  readonly startError = signal('');
  readonly url = environmentConsoleUrl;
  readonly filtered = computed(() => this.environments()
    .filter(e => `${e.projectName} ${e.projectDirectory}`.toLowerCase().includes(this.filter().trim().toLowerCase()))
    .sort((a, b) => Number(b.id === this.current()?.id) - Number(a.id === this.current()?.id)
      || Number(b.status === 'running') - Number(a.status === 'running')
      || a.projectName.localeCompare(b.projectName) || a.projectDirectory.localeCompare(b.projectDirectory)));

  toggle() {
    if (this.open()) { this.open.set(false); return; }
    this.filter.set('');
    this.open.set(true);
    this.changeDetector.detectChanges();
    this.search?.nativeElement.focus();
  }
  select(environment: Environment, event: MouseEvent) {
    if (event.button || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    this.open.set(false);
    sendCommand(this.elementRef.nativeElement, 'openEnvironment', environment);
    this.restoreFocus();
  }
  forget(environment: Environment) { sendCommand(this.elementRef.nativeElement, 'forgetProject', environment.id); }
  offerStart(environment: Environment) {
    this.open.set(false);
    this.selectedToStart.set(environment);
    this.startError.set('');
    this.changeDetector.detectChanges();
    this.startDialog?.nativeElement.showModal();
  }
  cancelStart(event: Event) { if (this.starting()) event.preventDefault(); }
  async startServer() {
    if (this.starting() || !this.selectedToStart()) return;
    this.starting.set(true);
    this.startError.set('');
    try {
      await sendCommand<Promise<void>>(this.elementRef.nativeElement, 'startEnvironment', this.selectedToStart()!.id);
      this.startDialog?.nativeElement.close();
    } catch (error: any) { this.startError.set(error?.error?.error || 'Unable to start the dev server. Check its status before retrying.'); }
    finally { this.starting.set(false); }
  }
  editName() {
    this.open.set(false);
    this.draft.set(this.currentName());
    this.error.set('');
    this.changeDetector.detectChanges();
    this.nameDialog?.nativeElement.showModal();
    this.nameInput?.nativeElement.select();
  }
  useFolderName() {
    this.draft.set(this.current()?.projectDirectory.replace(/[\\/]+$/, '').split(/[\\/]/).pop() || this.currentName());
    this.nameInput?.nativeElement.focus();
  }
  async saveName(event: Event) {
    event.preventDefault();
    if (this.saving() || !this.current() || !this.draft().trim()) return;
    this.saving.set(true);
    this.error.set('');
    try {
      await sendCommand<Promise<Environment>>(this.elementRef.nativeElement, 'renameEnvironment', {id: this.current()!.id, name: this.draft().trim()});
      this.nameDialog?.nativeElement.close();
    } catch (error: any) { this.error.set(error?.error?.error || 'Unable to rename the dev server.'); }
    finally { this.saving.set(false); }
  }
  restoreFocus() { this.trigger?.nativeElement.focus(); }
  @HostListener('document:click', ['$event'])
  @HostListener('document:focusin', ['$event']) dismissOutside(event: Event) {
    if (!this.elementRef.nativeElement.contains(event.target as Node)) this.open.set(false);
  }
  @HostListener('window:blur') dismissOnBlur() { this.open.set(false); }
  @HostListener('keydown', ['$event']) keydown(event: KeyboardEvent) {
    if (event.key === 'Escape' && this.open()) {
      event.preventDefault(); event.stopPropagation(); this.open.set(false); this.restoreFocus();
    }
  }
}
