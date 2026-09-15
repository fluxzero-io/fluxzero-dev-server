import {Component, ElementRef, ViewChild, afterEveryRender, inject, input, signal} from '@angular/core';
import {TestOutputLine} from './models';
import {sendCommand} from './dom-handlers';

@Component({selector:'dev-test-output',standalone:true,template:`
  <div class="test-output">
      <div class="test-output-toolbar">
        <button type="button" class="icon-button" [attr.aria-label]="following() ? 'Pause scrolling' : 'Resume scrolling'"
          [title]="following() ? 'Pause scrolling' : 'Resume scrolling'" (click)="following.set(!following())">
          <i [class]="following() ? 'bi bi-pause-fill' : 'bi bi-play-fill'" aria-hidden="true"></i>
        </button>
        <button type="button" class="icon-button" aria-label="Clear test output" title="Clear test output"
          [disabled]="clearing() || !lines().length" (click)="clear()"><i class="bi bi-trash" aria-hidden="true"></i></button>
      </div>
      @if(error()) {<p role="alert">{{error()}}</p>}
      <pre #output tabindex="0" aria-label="Live test output" (scroll)="scrolled()">@for(line of lines();track line.sequence) {<span>{{'[' + line.module + '] ' + line.text + '\n'}}</span>} @empty {<span class="empty-test-output">No test output.</span>}</pre>
  </div>
`})
export class TestOutputComponent {
  private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);
  lines=input<TestOutputLine[]>([]);
  following=signal(true);
  clearing=signal(false);
  error=signal('');
  @ViewChild('output') output?: ElementRef<HTMLElement>;
  constructor() {afterEveryRender(()=>{
    const element=this.output?.nativeElement;
    if(element && this.following())element.scrollTop=element.scrollHeight;
  });}
  scrolled() {
    const element=this.output?.nativeElement;
    if(element && element.scrollHeight-element.scrollTop-element.clientHeight>24)this.following.set(false);
  }
  async clear() {
    this.clearing.set(true); this.error.set('');
    try {await sendCommand<Promise<void>>(this.element.nativeElement, 'clearTestOutput');}
    catch {this.error.set('Unable to clear test output.');}
    finally {this.clearing.set(false);}
  }
}
