import {ProjectManagerComponent} from './project-manager.component';
import {ChangeDetectorRef, Component, computed, ElementRef, HostListener, inject, input, signal, ViewChild} from '@angular/core';
import {Environment, environmentConsoleUrl} from './models';
import {NgTemplateOutlet} from '@angular/common';
import {sendCommand} from './dom-handlers';

@Component({selector: 'dev-environment-selector', standalone: true, imports: [NgTemplateOutlet, ProjectManagerComponent], template: `
  <div class="dashboard-section-label">Project</div>
  <button #trigger class="server-picker-button" type="button" aria-label="Choose workspace" aria-haspopup="dialog"
    aria-controls="dev-server-picker" [attr.aria-expanded]="open()" [title]="currentName()" (click)="toggle()">
    <i class="bi bi-collection" aria-hidden="true"></i><span>{{currentName()}}</span><i class="bi bi-chevron-down" aria-hidden="true"></i>
  </button>
  @if(open()) {
    <div id="dev-server-picker" class="server-menu" role="dialog" aria-label="Dev servers">
      <div class="search"><i class="bi bi-search" aria-hidden="true"></i>
        <input #search type="search" placeholder="Search by name or folder" aria-label="Search dev servers" autocomplete="off"
          [value]="filter()" (input)="filter.set(search.value)"></div>
      <div class="server-options">
        @for(group of groups(); track group.label) {
          <section class="server-group" [attr.aria-label]="group.label">
            <h2 class="server-group-title">{{group.label}}</h2>
            @for(environment of group.environments; track environment.id) {
              <ng-template #entry>
                <span class="server-option-name">{{environment.projectName}}</span>
                <span class="server-path">{{environment.projectDirectory}}</span>
              </ng-template>
              @if(url(environment); as href) {
                <a class="server-option" [href]="href" (click)="select(environment, $event)">
                  <ng-container [ngTemplateOutlet]="entry"/>
                </a>
              } @else if(environment.status === 'stopped') {
                <button class="server-option inactive-server"
                  type="button" (click)="offerStart(environment)"><ng-container [ngTemplateOutlet]="entry"/></button>
              } @else {
                <div class="server-option" aria-disabled="true">
                  <ng-container [ngTemplateOutlet]="entry"/>
                  @if(environment.detail) {<span class="server-detail">{{environment.detail}}</span>}
                </div>
              }
            }
          </section>
        } @empty {<p class="server-empty">No matching dev servers</p>}
      </div>
      <button class="manage-projects secondary-button" (click)="open.set(false); manager.show(trigger)">Manage projects…</button>
    </div>
  }
  <dev-project-manager #manager [environments]="environments()"/>
  <dialog #startDialog class="start-dialog" aria-labelledby="server-start-title" (close)="restoreFocus()">
    <h2 id="server-start-title">{{selectedToStart()?.directoryExists ? 'Start dev server?' : 'Dev server unavailable'}}</h2>
    @if(selectedToStart()?.directoryExists) {<p>{{selectedToStart()?.projectName}} is inactive. Would you like to start it?</p>}
    @else {<p>The folder for {{selectedToStart()?.projectName}} no longer exists.</p>}
    <p class="server-path">{{selectedToStart()?.projectDirectory}}</p>
    @if(startError()) {<p role="alert">{{startError()}}</p>}
    <div class="dialog-actions">
      <button class="secondary-button dialog-cancel" type="button" autofocus [disabled]="starting() || removing()" (click)="startDialog.close()">Cancel</button>
      <button class="icon-button remove-project" type="button" [disabled]="starting() || removing()"
        [attr.aria-label]="'Remove ' + selectedToStart()?.projectName + ' from overview'" title="Remove from overview; keep project files"
        (click)="forget()"><i class="bi bi-trash" aria-hidden="true"></i></button>
      @if(selectedToStart()?.directoryExists) {<button class="primary-button" type="button" [disabled]="starting() || removing()" (click)="startServer()">
        @if(starting()) {<span class="spinner-border spinner-border-sm" aria-hidden="true"></span>}{{starting() ? 'Starting…' : 'Start dev server'}}
      </button>}
    </div>
  </dialog>
`, styles: `
  :host {display:block;position:relative;margin-bottom:20px;min-width:0;}
  .server-picker-button {display:grid;grid-template-columns:16px minmax(0,1fr) 14px;align-items:center;gap:5px;width:100%;min-height:38px;
    padding:0 8px;border:1px solid var(--dashboard-border);border-radius:6px;background:var(--dashboard-surface);color:var(--dashboard-text);text-align:left;}
  .server-picker-button span {overflow:hidden;font-size:15px;font-weight:700;text-overflow:ellipsis;white-space:nowrap;}
  .server-picker-button i {color:var(--dashboard-control-icon);font-size:14px;}
  .server-picker-button:hover {border-color:var(--dashboard-focus-border);}
  .server-picker-button:focus-visible {outline:2px solid var(--dashboard-focus-border);}
  .server-menu {position:absolute;top:calc(100% + 6px);left:0;z-index:30;width:380px;max-width:calc(100vw - 44px);max-height:calc(100dvh - 230px);
    display:flex;flex-direction:column;padding:6px;border:1px solid var(--dashboard-border);border-radius:8px;background:var(--dashboard-popover-bg);box-shadow:var(--dashboard-popover-shadow);}
  .search {flex:none;width:100%;margin:0 0 6px;padding:8px 10px;}
  .server-options {overflow-y:auto;overscroll-behavior:contain;min-height:0;}
  .server-group + .server-group {margin-top:6px;}
  .server-group-title {margin:0;padding:8px 10px;font-size:12px;font-weight:700;color:var(--dashboard-muted);}
  .server-detail {grid-column:1/-1;font-size:12px;color:var(--dashboard-muted);white-space:normal;}
  .server-option {display:grid;grid-template-columns:minmax(0,1fr) auto;gap:4px 8px;width:100%;padding:10px;border:0;border-radius:5px;
    background:transparent;color:var(--dashboard-text);text-align:left;text-decoration:none;font:inherit;}
  a.server-option:hover, button.server-option:hover {background:var(--dashboard-active-soft);}
  .server-option:focus-visible {outline:2px solid var(--dashboard-focus-border);outline-offset:-2px;}
  .server-option-name {grid-column:1;min-width:0;font-weight:600;overflow-wrap:anywhere;}
  .server-path {grid-column:1;min-width:0;font-size:12px;color:var(--dashboard-muted);overflow-wrap:anywhere;}
  .server-empty {padding:16px 10px;}
  .start-dialog {width:420px;max-width:calc(100vw - 24px);padding:24px;border:1px solid var(--dashboard-border);border-radius:8px;background:var(--dashboard-popover-bg);color:var(--dashboard-text);box-shadow:var(--dashboard-popover-shadow);}
  .start-dialog::backdrop {background:rgba(0,0,0,.4);}
  .start-dialog > p {margin-bottom:24px;}
  .start-dialog h2 {margin-bottom:24px;}
  .start-dialog [role=alert] {margin-bottom:16px;color:var(--dashboard-danger-text);}
`})
export class EnvironmentSelectorComponent {
  readonly elementRef = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly changeDetector = inject(ChangeDetectorRef);
  @ViewChild('trigger') trigger?: ElementRef<HTMLButtonElement>;
  @ViewChild('search') search?: ElementRef<HTMLInputElement>;
  @ViewChild('startDialog') startDialog?: ElementRef<HTMLDialogElement>;
  readonly environments = input<Environment[]>([]);
  readonly current = input<Environment>();
  readonly currentName = input('Select dev server');
  readonly open = signal(false);
  readonly filter = signal('');
  readonly selectedToStart = signal<Environment | null>(null);
  readonly starting = signal(false);
  readonly removing = signal(false);
  readonly startError = signal('');
  readonly url = environmentConsoleUrl;
  readonly groups = computed(() => {
    const currentId = this.current()?.id;
    const matches = this.environments()
      .filter(e => e.id !== currentId)
      .filter(e => `${e.projectName} ${e.projectDirectory}`.toLowerCase().includes(this.filter().trim().toLowerCase()))
      .sort((a, b) => a.projectName.localeCompare(b.projectName) || a.projectDirectory.localeCompare(b.projectDirectory));
    return [
      {label: 'Running', environments: matches.filter(e => e.status === 'running')},
      {label: 'Stopped', environments: matches.filter(e => e.status === 'stopped').slice(0,5)}
    ].filter(group => group.environments.length);
  });

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
  async forget() {
    if (this.starting() || this.removing() || !this.selectedToStart()) return;
    this.removing.set(true);
    try {
      await sendCommand<Promise<void>>(this.elementRef.nativeElement, 'forgetProject', this.selectedToStart()!.id);
      this.startDialog?.nativeElement.close();
      this.selectedToStart.set(null);
      this.toggle();
    } finally { this.removing.set(false); }
  }
  offerStart(environment: Environment) {
    this.open.set(false);
    this.selectedToStart.set(environment);
    this.startError.set('');
    this.changeDetector.detectChanges();
    this.startDialog?.nativeElement.showModal();
  }
  async startServer() {
    if (this.starting() || this.removing() || !this.selectedToStart()?.directoryExists) return;
    this.starting.set(true);
    this.startError.set('');
    try {
      await sendCommand<Promise<void>>(this.elementRef.nativeElement, 'startEnvironment', this.selectedToStart()!.id);
      this.startDialog?.nativeElement.close();
    } catch (error: any) { this.startError.set(error?.error?.error || 'Unable to start the dev server. Check its status before retrying.'); }
    finally { this.starting.set(false); }
  }
  restoreFocus() { this.trigger?.nativeElement.focus(); }
  @HostListener('document:click', ['$event'])
  @HostListener('document:focusin', ['$event']) dismissOutside(event: Event) {
    if (!this.elementRef.nativeElement.contains(event.target as Node)) this.open.set(false);
  }
  @HostListener('window:blur') dismissOnBlur() { this.open.set(false); }
  @HostListener('keydown', ['$event']) keydown(event: KeyboardEvent) {
    if (event.key === 'Escape' && this.open()) {
      event.preventDefault(); event.stopPropagation(); this.restoreFocus(); this.open.set(false);
    }
  }
}
