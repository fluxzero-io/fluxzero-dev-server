import {TestBed} from '@angular/core/testing';
import {provideHttpClient} from '@angular/common/http';
import {AppComponent} from './app.component';
import {ConsoleConnection} from './console-connection';

describe('Preview selector layout',()=>{
  it('keeps the native keyboard control and navigation inside narrow and wide toolbars',()=>{
    TestBed.configureTestingModule({imports:[AppComponent],providers:[provideHttpClient(),
      {provide:ConsoleConnection,useValue:{initialise:()=>{},close:()=>{}}}]});
    const fixture=TestBed.createComponent(AppComponent), app=fixture.componentInstance;
    app.status.set({project:'Preview',projectDirectory:'/preview',state:'running',runtime:'skipped',applications:'skipped',
      tests:'skipped',frontend:'running',monitoring:{enabled:false},frontends:[
        {id:'application',path:'/',state:'running'},{id:'inbox',path:'/inbox',state:'running'}]});
    fixture.detectChanges();
    const root=fixture.nativeElement as HTMLElement;
    const heading=root.querySelector<HTMLElement>('.application-heading')!;
    const select=root.querySelector<HTMLSelectElement>('.preview-frontend')!;
    // Native select retains built-in keyboard interaction without custom key handlers.
    select.focus();expect(document.activeElement).toBe(select);expect(select.tabIndex).toBe(0);
    const bounds=heading.getBoundingClientRect(), choice=select.getBoundingClientRect();
    expect(choice.width).toBeGreaterThan(80);expect(choice.left).toBeGreaterThanOrEqual(bounds.left);
    expect(choice.right).toBeLessThanOrEqual(bounds.right);
    for(const button of Array.from(heading.querySelectorAll('button'))) {
      const rect=button.getBoundingClientRect();expect(rect.right).toBeLessThanOrEqual(bounds.right);
    }
    if(innerWidth<=650) expect(getComputedStyle(select).gridColumn).toBe('1 / -1');
    fixture.destroy();
  });
});
