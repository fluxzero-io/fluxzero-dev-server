import {TestBed} from '@angular/core/testing';
import {ProgressPageComponent,progressCount} from './progress-page.component';
import {ProgressFeature,ProjectProgress} from './models';
const item=(id:string,status:ProgressFeature['status']):ProgressFeature=>({id,title:id==='cancel'?'Visitors can cancel tickets':'Visitors can book tickets',kind:'feature',status,description:'A clear customer outcome',acceptance:['The ticket is no longer valid'],createdAt:'2026-09-19T12:00:00Z',updatedAt:'2026-09-19T12:00:00Z',history:[{at:'2026-09-19T12:00:00Z',status,verification:status==='done'?'Confirmed admission is refused':''}]});
const data:ProjectProgress={revision:'r',error:null,data:{version:1,milestones:[{id:'m',title:'Ticket booking',description:'The booking journey',features:[item('book','done'),item('cancel','in_progress')]},{id:'old',title:'Launch',description:'',features:[item('launch','done')]}]}};
describe('Functional progress',()=>{
 it('opens active milestones and shows functional details and retained history on demand',()=>{
  const f=TestBed.createComponent(ProgressPageComponent);f.componentRef.setInput('progress',data);f.detectChanges();
  const root:HTMLElement=f.nativeElement;
  expect(root.querySelectorAll('.feature-toggle').length).toBe(2);
  expect(root.querySelectorAll('.milestone-toggle')[1].getAttribute('aria-expanded')).toBe('false');
  (root.querySelectorAll('.feature-toggle')[0] as HTMLButtonElement).click();f.detectChanges();
  expect(root.textContent).toContain('Confirmed admission is refused');
  f.componentRef.setInput('progress',{...data,revision:'new'});f.detectChanges();
  expect(root.textContent).toContain('Confirmed admission is refused');
  f.componentInstance.filter.set('done');f.detectChanges();
  expect(root.querySelectorAll('.feature-toggle').length).toBe(2);
  expect(root.querySelectorAll('.feature-toggle')[0].textContent).toContain('Done');
  f.destroy();
 });
 it('distinguishes empty, loading and invalid history without showing a misleading counter',()=>{
  const f=TestBed.createComponent(ProgressPageComponent);f.detectChanges();expect(f.nativeElement.textContent).toContain('Loading');
  f.componentRef.setInput('progress',{revision:'missing',error:null,data:{version:1,milestones:[]}});f.detectChanges();expect(f.nativeElement.textContent).toContain('Your project’s story starts here');
  f.componentRef.setInput('progress',{revision:null,data:null,error:'Could not load progress'});f.detectChanges();expect(f.nativeElement.querySelector('[role=alert]').textContent).toContain('Could not load');
  expect(f.nativeElement.querySelector('[role=progressbar]')).toBeNull();f.destroy();
 });
 it('counts functional items, including bugs, rather than milestones or effort',()=>{
  expect(progressCount(data)).toEqual({done:2,total:3,percent:67,label:'2 of 3 recorded features and fixes completed · 67%. Counts items, not remaining build time.'});
  expect(progressCount({...data,error:'invalid'})).toBeNull();
  expect(progressCount({revision:'missing',data:{version:1,milestones:[]},error:null})).toBeNull();
 });
});
