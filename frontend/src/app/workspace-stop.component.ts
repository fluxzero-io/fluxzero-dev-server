import {ChangeDetectorRef, Component, computed, effect, ElementRef, HostListener, inject, input, output, signal, ViewChild} from '@angular/core';

@Component({selector: 'dev-workspace-stop', standalone: true, template: `
  <div class="stop-control split-action">
    <button class="stop-action split-action-main" type="button" [attr.aria-label]="stopped() ? 'Start workspace' : 'Stop ' + selected().label"
      [title]="stopped() ? 'Start the workspace again' : selected().description" [disabled]="busy()" [attr.aria-busy]="working()"
      (click)="actionRequested.emit(stopped() ? 'start-workspace' : selected().key)">
      @if(working()) {<span class="spinner-border spinner-border-sm" role="status" aria-label="Working"></span>}
      @else {<i class="bi" [class.bi-play]="stopped()" [class.bi-stop]="!stopped()" aria-hidden="true"></i>}
      <span>{{stopped() ? 'Start' : scope() === 'stop-devserver' ? 'Stop all' : 'Stop'}}</span>
    </button>
    @if(!stopped()) {<button #trigger class="stop-choice split-action-choice" type="button" aria-label="Choose stop scope" aria-haspopup="menu"
      aria-controls="stop-scope-menu" [attr.aria-expanded]="open()" [disabled]="busy()" (click)="toggle()">
      <i class="bi bi-chevron-down" aria-hidden="true"></i>
    </button>}
  </div>
  @if(open() && !stopped()) {
    <div #menu id="stop-scope-menu" class="stop-menu" role="menu" aria-label="Stop scope">
      @for(option of options; track option.key) {
        <button type="button" role="menuitemradio" [attr.aria-checked]="scope() === option.key" tabindex="-1"
          [disabled]="busy()" (click)="choose(option.key)">
          <span><strong>{{option.label}}</strong><small>{{option.description}}</small></span>
          @if(scope() === option.key) {<i class="bi bi-check2" aria-hidden="true"></i>}
        </button>
      }
    </div>
  }`, styles: `
  :host {display:block;position:relative;max-width:100%;text-align:left;}
  .stop-menu {position:absolute;top:calc(100% + 6px);right:0;z-index:30;width:300px;max-width:calc(100vw - 48px);padding:6px;
    border:1px solid var(--dashboard-border);border-radius:10px;background:var(--dashboard-popover-bg);box-shadow:var(--dashboard-popover-shadow);}
  .stop-menu button {display:flex;align-items:center;justify-content:space-between;gap:10px;width:100%;padding:10px;
    border:0;border-radius:6px;background:transparent;color:var(--dashboard-text);text-align:left;}
  .stop-menu button:hover:not(:disabled) {background:var(--dashboard-action-hover);}
  .stop-menu button[aria-checked=true] {background:var(--dashboard-active-soft);}
  .stop-menu strong {font-size:13px;font-weight:600;}
  .stop-menu small {display:block;font-size:12px;line-height:1.5;color:var(--dashboard-muted);margin-top:4px;}
`})
export class WorkspaceStopComponent {
  readonly busy = input(false);
  readonly working = input(false);
  readonly stopped = input(false);
  readonly actionRequested = output<string>();
  readonly scope = signal('stop-workspace');
  readonly open = signal(false);
  readonly options = [
    {key:'stop-workspace', label:'Workspace', description:'Stop apps, UI servers, tests and supporting services. Keep this dashboard available to start again.'},
    {key:'stop-devserver', label:'Everything', description:'Also close the dev server and dashboard. Start again from the CLI or another active dashboard.'}
  ];
  readonly selected = computed(() => this.options.find(option => option.key === this.scope())!);
  private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly changes = inject(ChangeDetectorRef);
  @ViewChild('trigger') trigger?: ElementRef<HTMLButtonElement>;
  @ViewChild('menu') menu?: ElementRef<HTMLElement>;
  constructor() { effect(() => { if (this.busy()) this.close(); }); effect(() => { this.stopped(); this.scope.set('stop-workspace'); this.close(); }); }
  toggle() {
    if (this.busy() || this.stopped()) return;
    this.open.set(!this.open());
    if (this.open()) {
      this.changes.detectChanges();
      (this.menu?.nativeElement.querySelector<HTMLButtonElement>('[aria-checked=true]:not(:disabled)')
        || this.menu?.nativeElement.querySelector<HTMLButtonElement>('button:not(:disabled)'))?.focus();
    }
  }
  choose(key: string) {
    if (this.busy() || this.stopped()) return;
    if (!this.options.some(option => option.key === key)) return;
    this.scope.set(key);
    this.close(true);
  }
  private close(restoreFocus = false) {
    this.open.set(false);
    if (restoreFocus) this.trigger?.nativeElement.focus();
  }
  @HostListener('document:click', ['$event'])
  @HostListener('document:focusin', ['$event']) dismissOutside(event: Event) {
    if (!this.element.nativeElement.contains(event.target as Node)) this.close();
  }
  @HostListener('window:blur') dismissOnBlur() { this.close(); }
  @HostListener('keydown', ['$event']) keydown(event: KeyboardEvent) {
    if (event.key === 'Tab') { if (this.open()) this.close(true); return; }
    if (event.key === 'Escape') {
      if (this.open()) { event.preventDefault(); event.stopPropagation(); this.close(true); }
      return;
    }
    if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
    if (!this.open() && event.target !== this.trigger?.nativeElement) return;
    event.preventDefault();
    if (!this.open()) { this.toggle(); return; }
    const items = Array.from(this.menu!.nativeElement.querySelectorAll<HTMLButtonElement>('button:not(:disabled)'));
    const current = items.indexOf(document.activeElement as HTMLButtonElement);
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1
      : (current + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length;
    items[next]?.focus();
  }
}
