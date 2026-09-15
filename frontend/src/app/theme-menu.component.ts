import {ChangeDetectorRef, Component, computed, ElementRef, HostListener, inject, input, signal, ViewChild} from '@angular/core';
import {sendCommand} from './dom-handlers';

@Component({selector: 'dev-theme-menu', standalone: true, template: `
  <button #trigger class="icon-button theme-trigger" type="button" [attr.aria-label]="'Theme: ' + selected().label"
    [title]="'Theme: ' + selected().label" aria-haspopup="menu" aria-controls="theme-menu" [attr.aria-expanded]="open()" (click)="toggle()">
    <i [class]="'bi bi-' + selected().icon" aria-hidden="true"></i>
  </button>
  @if(open()) {
    <div #menu id="theme-menu" class="theme-menu" role="menu" aria-label="Appearance">
      @for(option of options; track option.key) {
        <button type="button" role="menuitemradio" [attr.aria-checked]="theme() === option.key" tabindex="-1"
          [class.selected]="theme() === option.key" (click)="select(option.key)">
          <span>{{option.label}}</span><i [class]="'bi bi-' + option.icon" aria-hidden="true"></i>
        </button>
      }
    </div>
  }`, styles: `
  :host {position:relative;display:block;flex-shrink:0;}
  .theme-trigger {font-size:18px;}
  .theme-menu {position:absolute;right:0;bottom:calc(100% + 10px);z-index:1;display:flex;flex-direction:column;gap:4px;
    width:180px;padding:6px;background:var(--dashboard-popover-bg);border:1px solid var(--dashboard-border);
    border-radius:8px;box-shadow:var(--dashboard-popover-shadow);}
  .theme-menu button {display:flex;align-items:center;justify-content:space-between;gap:24px;min-height:40px;padding:8px 12px;
    border:0;border-radius:5px;background:transparent;color:var(--dashboard-text);text-align:left;font-size:14px;}
  .theme-menu button:hover {background:var(--dashboard-panel-soft);}
  .theme-menu button.selected {background:var(--dashboard-active-soft);color:var(--dashboard-active-foreground);font-weight:700;
    box-shadow:inset 3px 0 var(--dashboard-active);}
  .theme-menu i {font-size:18px;}
`})
export class ThemeMenuComponent {
  readonly elementRef = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly changeDetector = inject(ChangeDetectorRef);
  @ViewChild('trigger') trigger?: ElementRef<HTMLButtonElement>;
  @ViewChild('menu') menu?: ElementRef<HTMLElement>;
  readonly theme = input('system');
  readonly open = signal(false);
  readonly options = [
    {key: 'light', label: 'Light', icon: 'sun'},
    {key: 'dark', label: 'Dark', icon: 'moon-stars'},
    {key: 'system', label: 'System', icon: 'display'}
  ];
  readonly selected = computed(() => this.options.find(option => option.key === this.theme()) || this.options[2]);

  toggle() {
    if (this.open()) this.close();
    else {
      this.open.set(true);
      this.changeDetector.detectChanges();
      this.menu?.nativeElement.querySelector<HTMLButtonElement>('[aria-checked="true"]')?.focus();
    }
  }
  select(theme: string) {
    sendCommand(this.elementRef.nativeElement, 'setTheme', theme);
    this.close(true);
  }
  private close(restoreFocus = false) {
    this.open.set(false);
    if (restoreFocus) this.trigger?.nativeElement.focus();
  }
  @HostListener('document:click', ['$event'])
  @HostListener('document:focusin', ['$event'])
  dismissOutside(event: Event) {
    if (!this.elementRef.nativeElement.contains(event.target as Node)) this.close();
  }
  @HostListener('window:blur') dismissOnBlur() { this.close(); }
  @HostListener('keydown', ['$event']) onKeydown(event: KeyboardEvent) {
    if (event.key === 'Tab') { if (this.open()) this.close(true); return; }
    if (event.key === 'Escape') {
      if (this.open()) { event.preventDefault(); event.stopPropagation(); this.close(true); }
      return;
    }
    if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    if (!this.open()) { this.toggle(); return; }
    const items = Array.from(this.menu!.nativeElement.querySelectorAll<HTMLButtonElement>('[role="menuitemradio"]'));
    const current = items.indexOf(document.activeElement as HTMLButtonElement);
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1
      : (current + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length;
    items[next]?.focus();
  }
}
