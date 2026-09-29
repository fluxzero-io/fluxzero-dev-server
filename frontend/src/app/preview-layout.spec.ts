import {TestBed} from '@angular/core/testing';
import {provideHttpClient} from '@angular/common/http';
import {AppComponent} from './app.component';
import {ConsoleConnection} from './console-connection';

describe('Preview navigation layout',()=>{
  it('keeps keyboard choices inside the sidebar or mobile drawer and leaves room for the toolbar',()=>{
    TestBed.configureTestingModule({imports:[AppComponent],providers:[provideHttpClient(),
      {provide:ConsoleConnection,useValue:{initialise:()=>{},close:()=>{}}}]});
    const fixture=TestBed.createComponent(AppComponent), app=fixture.componentInstance;
    app.status.set({project:'Preview',projectDirectory:'/preview',state:'running',runtime:'skipped',applications:'skipped',
      tests:'skipped',frontend:'running',monitoring:{enabled:false},frontends:[
        {id:'application',path:'/',state:'running'},{id:'inbox',path:'/inbox',state:'running'}]});
    app.navigate('application');
    if(innerWidth<=650) app.menuOpen.set(true);
    fixture.detectChanges();
    const root=fixture.nativeElement as HTMLElement;
    const heading=root.querySelector<HTMLElement>('.application-heading')!;
    const choice=root.querySelector<HTMLButtonElement>('.preview-frontend-item')!;
    choice.focus();expect(document.activeElement).toBe(choice);expect(choice.tabIndex).toBe(0);
    const sidebar=root.querySelector<HTMLElement>('.dashboard-sidebar')!.getBoundingClientRect();
    const rect=choice.getBoundingClientRect();
    expect(rect.width).toBeGreaterThan(80);expect(rect.left).toBeGreaterThanOrEqual(sidebar.left);
    expect(rect.right).toBeLessThanOrEqual(sidebar.right);
    expect(root.querySelector('.preview-frontend')).toBeNull();
    const bounds=heading.getBoundingClientRect();
    for(const button of Array.from(heading.querySelectorAll('button'))) {
      const rect=button.getBoundingClientRect();expect(rect.right).toBeLessThanOrEqual(bounds.right);
    }
    fixture.destroy();
  });
});
