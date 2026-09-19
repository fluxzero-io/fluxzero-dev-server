import {TestBed} from '@angular/core/testing';
import {provideHttpClient} from '@angular/common/http';
import {provideHttpClientTesting,HttpTestingController} from '@angular/common/http/testing';
import {StartupComponent} from './startup.component';

describe('Startup command JSON',()=>{
  it('loads on expansion only and copies exactly the displayed JSON',async()=>{
    TestBed.configureTestingModule({providers:[provideHttpClient(),provideHttpClientTesting()]});
    const fixture=TestBed.createComponent(StartupComponent);
    const http=TestBed.inject(HttpTestingController);
    fixture.componentRef.setInput('startup',{state:'succeeded',sessionId:'s',actions:[{id:'a',hash:'h',name:'Create home',state:'succeeded'}]});
    fixture.detectChanges();http.expectNone('startup-command.json');
    expect(fixture.nativeElement.querySelector('.json-toolbar')).toBeNull();
    fixture.nativeElement.querySelector('.startup-row').click();fixture.detectChanges();
    const request=http.expectOne('startup-command.json');expect(request.request.body).toEqual({sessionId:'s',id:'a',hash:'h'});
    const value={type:'CreateHome',revision:0,payload:{name:'<Home>'}};request.flush(value);fixture.detectChanges();
    const rendered=fixture.nativeElement.querySelector('pre').textContent;
    expect(rendered).toBe(JSON.stringify(value,null,2));expect(fixture.nativeElement.querySelector('Home')).toBeNull();
    const clipboard=spyOn(navigator.clipboard,'writeText').and.resolveTo();
    fixture.nativeElement.querySelector('.json-toolbar button').click();await fixture.whenStable();fixture.detectChanges();
    expect(clipboard).toHaveBeenCalledOnceWith(rendered);expect(fixture.nativeElement.textContent).toContain('Copied');
    fixture.nativeElement.querySelector('.startup-row').click();fixture.detectChanges();expect(fixture.nativeElement.querySelector('pre')).toBeNull();
    http.verify();fixture.destroy();
  });
  it('allows retry after load failure and reports clipboard rejection',async()=>{
    TestBed.configureTestingModule({providers:[provideHttpClient(),provideHttpClientTesting()]});
    const fixture=TestBed.createComponent(StartupComponent);const http=TestBed.inject(HttpTestingController);
    fixture.componentRef.setInput('startup',{state:'succeeded',sessionId:'s',actions:[{id:'a',hash:'h',name:'Create home',state:'succeeded'}]});
    fixture.detectChanges();fixture.nativeElement.querySelector('.startup-row').click();fixture.detectChanges();
    http.expectOne('startup-command.json').flush({}, {status:404,statusText:'Not found'});fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('pre')).toBeNull();
    fixture.nativeElement.querySelector('[role="alert"] button').click();http.expectOne('startup-command.json').flush({payload:{}});fixture.detectChanges();
    spyOn(navigator.clipboard,'writeText').and.rejectWith(new Error('Denied'));
    fixture.nativeElement.querySelector('.json-toolbar button').click();await fixture.whenStable();fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Could not copy.');http.verify();fixture.destroy();
  });
});
