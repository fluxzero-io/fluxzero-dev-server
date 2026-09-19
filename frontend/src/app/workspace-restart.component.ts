import {ChangeDetectorRef, Component, computed, effect, ElementRef, HostListener, inject, input, output, signal, ViewChild} from '@angular/core';

@Component({selector: 'dev-workspace-restart', standalone: true, template: `
  <div class="restart-control split-action">
    <button class="restart-action split-action-main" type="button" [attr.aria-label]="'Restart ' + selected().label"
      [title]="selected().description + (selected().warning ? ' ' + selected().warning : '')" [disabled]="busy() || !supported(selected().key)" [attr.aria-busy]="restarting()"
      (click)="restart.emit(selected().key)">
      @if(restarting()) {<span class="spinner-border spinner-border-sm" role="status" aria-label="Restarting"></span>}
      @else {<i class="bi bi-arrow-clockwise" aria-hidden="true"></i>}
      <span><span class="restart-verb">Restart </span>{{selected().label}}</span>
    </button>
    <button #trigger class="restart-choice split-action-choice" type="button" aria-label="Choose restart scope" aria-haspopup="menu"
      aria-controls="restart-scope-menu" [attr.aria-expanded]="open()" [disabled]="busy()" (click)="toggle()">
      <i class="bi bi-chevron-down" aria-hidden="true"></i>
    </button>
  </div>
  @if(open()) {
    <div #menu id="restart-scope-menu" class="restart-menu" role="menu" aria-label="Restart scope">
      @for(option of options; track option.key) {
        <button type="button" role="menuitemradio" [attr.aria-checked]="scope() === option.key" tabindex="-1"
          [disabled]="busy() || !supported(option.key)" (click)="choose(option.key)">
          <span><strong>{{option.label}}</strong><small>{{option.description}} @if(option.warning) {<strong class="reset-warning">{{option.warning}}</strong>}</small></span>
          @if(scope() === option.key) {<i class="bi bi-check2" aria-hidden="true"></i>}
        </button>
      }
    </div>
  }`, styles: `
  :host {display:block;position:relative;max-width:100%;text-align:left;}
  .restart-menu {position:absolute;top:calc(100% + 6px);right:0;z-index:30;width:300px;max-width:calc(100vw - 48px);padding:6px;
    border:1px solid var(--dashboard-border);border-radius:10px;background:var(--dashboard-popover-bg);box-shadow:var(--dashboard-popover-shadow);}
  .restart-menu button {display:flex;align-items:center;justify-content:space-between;gap:10px;width:100%;padding:10px;
    border:0;border-radius:6px;background:transparent;color:var(--dashboard-text);text-align:left;}
  .restart-menu button:hover:not(:disabled) {background:var(--dashboard-action-hover);}
  .restart-menu button[aria-checked=true] {background:var(--dashboard-active-soft);}
  .restart-menu strong {font-size:13px;font-weight:600;}
  .restart-menu .reset-warning {font-size:inherit;font-weight:700;}
  @media(max-width:650px) {.restart-verb {display:none;}}
  .restart-menu small {display:block;font-size:12px;line-height:1.5;color:var(--dashboard-muted);margin-top:4px;}
`})
export class WorkspaceRestartComponent {
  readonly busy = input(false);
  readonly action = input<string | null>(null);
  readonly appsSupported = input(false);
  readonly environmentSupported = input(false);
  readonly restart = output<string>();
  readonly scope = signal(this.savedScope());
  readonly open = signal(false);
  readonly options = [
    {key:'restart-application', label:'Apps', warning:'', description:'Restart backend apps and UI servers. Keep shared services running.'},
    {key:'restart-devserver', label:'All', description:'Restart apps, UI, the dev server and all supporting services.', warning:'This resets all data.'}
  ];
  readonly selected = computed(() => this.options.find(option => option.key === this.scope())!);
  readonly restarting = computed(() => this.action() === 'restart-application' || this.action() === 'restart-devserver');
  private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly changes = inject(ChangeDetectorRef);
  @ViewChild('trigger') trigger?: ElementRef<HTMLButtonElement>;
  @ViewChild('menu') menu?: ElementRef<HTMLElement>;
  constructor() { effect(() => { if (this.busy()) this.close(); }); }
  private savedScope() {
    try { return localStorage.getItem('devRestartScope') === 'restart-devserver' ? 'restart-devserver' : 'restart-application'; }
    catch { return 'restart-application'; }
  }
  supported(key: string) { return key === 'restart-application' ? this.appsSupported() : this.environmentSupported(); }
  toggle() {
    if (this.busy()) return;
    this.open.set(!this.open());
    if (this.open()) {
      this.changes.detectChanges();
      (this.menu?.nativeElement.querySelector<HTMLButtonElement>('[aria-checked=true]:not(:disabled)')
        || this.menu?.nativeElement.querySelector<HTMLButtonElement>('button:not(:disabled)'))?.focus();
    }
  }
  choose(key: string) {
    if (this.busy() || !this.supported(key)) return;
    if (!this.options.some(option => option.key === key)) return;
    this.scope.set(key);
    try { localStorage.setItem('devRestartScope', key); } catch { /* Selection also works without storage. */ }
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
