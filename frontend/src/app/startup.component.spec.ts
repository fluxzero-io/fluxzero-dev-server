import {TestBed} from '@angular/core/testing';
import {StartupComponent} from './startup.component';
import {StartedAgoComponent, startedAgo} from './started-ago.component';

describe('Workspace startup', () => {
  it('keeps successful actions compact and expands their individual results', () => {
    const fixture=TestBed.createComponent(StartupComponent);
    fixture.componentRef.setInput('startup',{state:'succeeded',actions:[{id:'a',name:'Create home',state:'succeeded'}]});
    fixture.detectChanges();
    const root:HTMLElement=fixture.nativeElement;
    expect(root.textContent).toContain('1 / 1 completed');
    expect((root.querySelector('#startup-actions') as HTMLElement).hidden).toBeTrue();
    (root.querySelector('button') as HTMLButtonElement).click();fixture.detectChanges();
    expect((root.querySelector('#startup-actions') as HTMLElement).hidden).toBeFalse();
    expect(root.querySelector('.startup-row')?.textContent).toContain('Create home');
    fixture.destroy();
  });
  it('exposes failures and blocked actions, and hides an empty startup', () => {
    const fixture=TestBed.createComponent(StartupComponent);
    fixture.componentRef.setInput('startup',{state:'failed',actions:[{id:'a',name:'Create home',state:'failed'},{id:'b',name:'Add floor',state:'blocked'}]});
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('button').getAttribute('aria-expanded')).toBe('true');
    expect(fixture.nativeElement.textContent).toContain('Failed');
    expect(fixture.nativeElement.textContent).toContain('Blocked');
    fixture.componentRef.setInput('startup',{state:'failed',actions:[]});fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Startup actions could not be loaded.');
    fixture.componentRef.setInput('startup',{state:'idle',actions:[]});fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('section')).toBeNull();
    fixture.destroy();
  });
  it('formats elapsed time at unit boundaries and exposes the exact start time', () => {
    const start=Date.UTC(2026,8,19,12);
    for(const [minutes,label] of [[0,'just now'],[1,'1 minute ago'],[59,'59 minutes ago'],[60,'1 hour ago'],[120,'2 hours ago'],[1440,'1 day ago'],[2880,'2 days ago']] as const) {
      expect(startedAgo(start,start+minutes*60000)).toBe('Started '+label);
    }
    const fixture=TestBed.createComponent(StartedAgoComponent);
    fixture.componentRef.setInput('startedAt',start);fixture.detectChanges();
    const time:HTMLTimeElement=fixture.nativeElement.querySelector('time');
    expect(time.dateTime).toBe(new Date(start).toISOString());expect(time.title).toContain('2026');
    fixture.componentInstance.now.set(start+120*60000);fixture.detectChanges();
    expect(time.textContent).toBe('Started 2 hours ago');
    fixture.componentRef.setInput('startedAt',undefined);fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('time')).toBeNull();
    fixture.destroy();
  });
});
