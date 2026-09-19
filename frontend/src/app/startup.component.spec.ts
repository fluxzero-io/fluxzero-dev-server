import {TestBed} from '@angular/core/testing';
import {StartupComponent} from './startup.component';
import {StartedAgoComponent, startedAgo} from './started-ago.component';

describe('Workspace startup', () => {
  it('shows successful actions immediately and filters/searches the list', () => {
    const fixture=TestBed.createComponent(StartupComponent);
    fixture.componentRef.setInput('startup',{state:'succeeded',actions:[{id:'a',name:'Create home',state:'succeeded'}]});
    fixture.detectChanges();
    const root:HTMLElement=fixture.nativeElement;
    expect(root.querySelector('h2')?.textContent).toBe('Startup data');
    expect(root.querySelector('.startup-filters button')?.getAttribute('aria-pressed')).toBe('true');
    expect(root.querySelector('.startup-row')?.textContent).toContain('Create home');
    fixture.componentInstance.select('failed');fixture.detectChanges();
    expect(root.querySelector('.startup-row')).toBeNull();
    expect(root.textContent).toContain('No failed actions.');
    fixture.componentInstance.select('all');
    const input=root.querySelector('input')!;input.value='HOME';input.dispatchEvent(new Event('input'));fixture.detectChanges();
    expect(root.querySelector('.startup-row')).not.toBeNull();
    input.value='floor';input.dispatchEvent(new Event('input'));fixture.detectChanges();
    expect(root.textContent).toContain('No matching actions in this filter.');
    fixture.destroy();
  });
  it('exposes failures and blocked actions, and hides an empty startup', () => {
    const fixture=TestBed.createComponent(StartupComponent);
    fixture.componentRef.setInput('startup',{state:'failed',actions:[{id:'a',name:'Create home',state:'failed'},{id:'b',name:'Add floor',state:'blocked'}]});
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Failed');
    expect(fixture.nativeElement.textContent).toContain('Blocked');
    fixture.componentInstance.select('pending');fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.startup-row').length).toBe(1);
    expect(fixture.nativeElement.querySelector('.startup-row').textContent).toContain('Add floor');
    fixture.componentRef.setInput('startup',{state:'failed',actions:[]});fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Startup actions could not be loaded.');
    fixture.componentRef.setInput('startup',{state:'idle',actions:[]});fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('section')).toBeNull();
    fixture.destroy();
  });
  it('bounds large lists and preserves visible entries during refresh', () => {
    const fixture=TestBed.createComponent(StartupComponent);
    const data={state:'succeeded',actions:Array.from({length:75},(_,i)=>({id:String(i),name:'Action '+i,state:'succeeded'}))};
    fixture.componentRef.setInput('startup',data);fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.startup-row').length).toBe(50);
    fixture.nativeElement.querySelector('.startup-pagination button').click();fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.startup-row').length).toBe(75);
    fixture.componentRef.setInput('startup',{...data});fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.startup-row').length).toBe(75);
    fixture.componentInstance.select('completed');fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.startup-row').length).toBe(50);
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
