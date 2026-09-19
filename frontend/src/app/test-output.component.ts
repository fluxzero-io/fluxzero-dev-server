import {Component, ElementRef, ViewChild, afterEveryRender, inject, input, output as outputEvent, signal} from '@angular/core';
import {TestOutputLine} from './models';
import {sendCommand} from './dom-handlers';

@Component({selector:'dev-test-output',standalone:true,template:`
  <div class="test-output">
      <div class="test-output-toolbar">
        <button type="button" class="icon-button" [attr.aria-label]="pauseLabel()" [title]="pauseLabel()"
          [disabled]="pauseBusy()" [attr.aria-pressed]="paused()" (click)="toggleTests.emit()">
          <i [class]="paused() ? 'bi bi-play-fill' : 'bi bi-pause-fill'" aria-hidden="true"></i>
        </button>
      </div>
      <div class="test-output-area">
        <button type="button" class="icon-button clear-test-output" aria-label="Clear test output" title="Clear test output"
          [disabled]="clearing() || !lines().length" (click)="clear()"><i class="bi bi-eraser" aria-hidden="true"></i></button>
      <pre #output tabindex="0" aria-label="Live test output" (scroll)="scrolled()">@for(line of lines();track line.sequence) {<span>{{'[' + line.module + '] ' + line.text + '\n'}}</span>} @empty {<span class="empty-test-output">No test output.</span>}</pre>
      </div>
      @if(error()) {<p role="alert">{{error()}}</p>}
  </div>
`})
export class TestOutputComponent {
  private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);
  lines=input<TestOutputLine[]>([]);
  paused=input(false);
  pauseBusy=input(false);
  toggleTests=outputEvent<void>();
  private following=true;
  pauseLabel() {
    return this.paused() ? 'Resume automatic tests' : 'Pause automatic tests';
  }
  clearing=signal(false);
  error=signal('');
  @ViewChild('output') output?: ElementRef<HTMLElement>;
  constructor() {afterEveryRender(()=>{
    const element=this.output?.nativeElement;
    if(element && this.following)element.scrollTop=element.scrollHeight;
  });}
  ngOnChanges() { this.scrolled(); }
  scrolled() {
    const element=this.output?.nativeElement;
    if(element)this.following=element.scrollHeight-element.scrollTop-element.clientHeight<=4;
  }
  async clear() {
    this.clearing.set(true); this.error.set('');
    try {await sendCommand<Promise<void>>(this.element.nativeElement, 'clearTestOutput');}
    catch {this.error.set('Unable to clear test output.');}
    finally {this.clearing.set(false);}
  }
}
