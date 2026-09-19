import {Component, computed, input, signal} from '@angular/core';
import {DatePipe} from '@angular/common';
import {ProgressFeature, ProgressMilestone, ProgressState, ProjectProgress} from './models';

export function progressCount(progress:ProjectProgress|undefined) {
  if(!progress?.data || progress.error) return null;
  const features=progress.data.milestones.flatMap(m=>m.features), total=features.length;
  if(!total) return null;
  const done=features.filter(f=>f.status==='done').length;
  return {done,total,percent:Math.round(done/total*100),label:`${done} of ${total} completed · ${Math.round(done/total*100)}%`};
}
@Component({selector:'dev-progress',standalone:true,imports:[DatePipe],template:`
  <section class="page progress-page" aria-labelledby="progress-title">
    <header class="page-heading"><div><h1 id="progress-title">Progress</h1><p>What’s taking shape, and what’s already done.</p></div></header>
    @if(progress()?.error) {
      <div class="progress-card empty" role="alert">{{progress()?.error}}</div>
    } @else if(!progress()) {
      <p class="muted">Loading project history…</p>
    } @else if(!milestones().length) {
      <div class="progress-card empty"><i class="bi bi-list-check" aria-hidden="true"></i><h2>Your project’s story starts here</h2><p>Your agent keeps track of features and fixes as you build together.</p></div>
    } @else {
      <div class="progress-card">
        @if(count(); as summary) {
          <div class="progress-summary"><span><strong>{{summary.done}}</strong> / {{summary.total}} completed</span><span class="muted">{{summary.percent}}%</span></div>
          <div class="progress-track" role="progressbar" aria-label="Completed features and fixes" [attr.aria-valuenow]="summary.done" aria-valuemin="0" [attr.aria-valuemax]="summary.total" [attr.aria-valuetext]="summary.label"><span [style.width.%]="summary.percent"></span></div>
        }
        <div class="progress-filters" role="group" aria-label="Filter progress">
          @for(option of filters; track option.key) {<button type="button" [class.selected]="filter()===option.key" [attr.aria-pressed]="filter()===option.key" (click)="filter.set(option.key)">{{option.label}}<span>{{filterCount(option.key)}}</span></button>}
        </div>
        @for(milestone of visibleMilestones(); track milestone.id) {
          <section class="milestone">
            <button type="button" class="milestone-toggle" [attr.aria-expanded]="isOpen(milestone)" (click)="toggleMilestone(milestone)">
              <i [class]="'bi ' + icon(milestoneState(milestone))" [class.done]="milestoneState(milestone)==='done'" [class.active]="milestoneState(milestone)==='in_progress'" aria-hidden="true"></i>
              <i class="bi bi-chevron-right chevron" [class.open]="isOpen(milestone)" aria-hidden="true"></i>
              <span class="milestone-title">{{milestone.title}}</span><span class="count">{{completed(milestone)}} / {{milestone.features.length}} completed</span>
            </button>
            @if(isOpen(milestone)) {
              @if(milestone.description) {<p class="milestone-description">{{milestone.description}}</p>}
              <div class="features">
              @for(feature of visibleFeatures(milestone); track feature.id) {
                <div class="feature">
                  <button type="button" class="feature-toggle" [attr.aria-expanded]="expanded().has(feature.id)" (click)="toggleFeature(feature.id)">
                    <i [class]="'bi ' + icon(feature.status)" [class.done]="feature.status==='done'" [class.active]="feature.status==='in_progress'" aria-hidden="true"></i>
                    <span class="feature-name">{{feature.title}}@if(feature.kind==='bug') {<span class="bug-kind">Fix</span>}</span>
                    <span class="state" [class.done]="feature.status==='done'">{{label(feature.status)}}</span>
                    <i class="bi bi-chevron-right chevron" [class.open]="expanded().has(feature.id)" aria-hidden="true"></i>
                  </button>
                  @if(expanded().has(feature.id)) {
                    <div class="feature-details">
                      @if(feature.description) {<p>{{feature.description}}</p>}
                      @if(feature.acceptance.length) {<h3>What it delivers</h3><ul>@for(criterion of feature.acceptance; track $index) {<li>{{criterion}}</li>}</ul>}
                      <h3>History</h3>
                      <ol class="history">@for(change of feature.history; track $index) {<li><div><span>{{label(change.status)}}</span><time [attr.datetime]="change.at" [title]="(change.at | date:'medium') || ''">{{change.at | date:'mediumDate'}}</time></div>@if(change.verification) {<p>{{change.verification}}</p>}</li>}</ol>
                    </div>
                  }
                </div>
              } @empty {<p class="muted">No features yet.</p>}
              </div>
            }
          </section>
        } @empty {<p class="no-results muted">No items in this view yet.</p>}
      </div>
    }
  </section>`,styles:`
    header {margin-bottom:28px;}h1 {font-size:32px;font-weight:800;margin:0;}header p {color:var(--dashboard-muted);margin:4px 0 0;font-size:14px;}
    .progress-card {padding:24px;border:1px solid var(--dashboard-border);border-radius:18px;background:var(--dashboard-surface);box-shadow:var(--dashboard-card-shadow);}
    .empty {padding:48px 24px;text-align:center;}.empty>i {font-size:28px;color:var(--dashboard-active);}.empty h2 {font-size:18px;margin:16px 0 8px;}.empty p,.muted {color:var(--dashboard-muted);}.empty p {font-size:14px;}
    .progress-summary {display:flex;justify-content:space-between;font-size:13px;gap:12px;}.progress-summary strong {font-size:20px;}
    .progress-track {height:5px;background:var(--dashboard-active-soft);border-radius:5px;overflow:hidden;margin:12px 0 22px;}.progress-track span {display:block;height:100%;background:var(--dashboard-success-text);}
    .progress-filters {display:flex;gap:24px;border-bottom:1px solid var(--dashboard-border);overflow-x:auto;}
    .progress-filters button {border:0;border-bottom:2px solid transparent;padding:10px 3px 12px;background:transparent;color:var(--dashboard-muted);font-size:13px;white-space:nowrap;cursor:pointer;}
    .progress-filters button.selected {border-bottom-color:var(--dashboard-active);color:var(--dashboard-active);}
    .progress-filters span {margin-left:6px;background:var(--dashboard-active-soft);font-size:11px;padding:1px 5px;border-radius:4px;}
    .milestone+.milestone {border-top:1px solid var(--dashboard-border);}.milestone-toggle,.feature-toggle {display:flex;align-items:center;gap:12px;width:100%;text-align:left;background:transparent;border:0;color:var(--dashboard-text);cursor:pointer;}
    .milestone-toggle {padding:20px 0;font-size:15px;}.milestone-title {font-weight:600;flex:1;}.milestone-title,.feature-name {min-width:0;overflow-wrap:anywhere;}
    .count,.state {color:var(--dashboard-muted);font-size:12px;white-space:nowrap;}.milestone-description {margin:0 0 16px 48px;color:var(--dashboard-muted);font-size:13px;}
    .features {margin:0 0 12px 48px;}.feature+.feature {border-top:1px solid var(--dashboard-border);}.feature-toggle {padding:15px 0;font-size:14px;}.feature-name {flex:1;}.bug-kind {font-size:10px;margin-left:8px;color:var(--dashboard-muted);}
    .chevron {font-size:11px;color:var(--dashboard-muted);transition:transform .15s;}.chevron.open {transform:rotate(90deg);}.done {color:var(--dashboard-success-text);}.active {color:var(--dashboard-active);}
    button:hover .milestone-title,button:hover .feature-name {color:var(--dashboard-active);}button:focus-visible {outline:2px solid var(--dashboard-active);outline-offset:2px;border-radius:4px;}
    .feature-details {padding:0 24px 18px 28px;font-size:13px;line-height:1.6;}.feature-details p {white-space:pre-wrap;overflow-wrap:anywhere;margin:0 0 14px;}h3 {font-size:12px;font-weight:600;margin:16px 0 8px;color:var(--dashboard-muted);}.feature-details ul {padding-left:18px;}.history {list-style:none;padding:0;}.history li {border-left:2px solid var(--dashboard-border);padding:0 0 12px 14px;}.history li:last-child {padding-bottom:0;}.history li>div {display:flex;gap:16px;justify-content:space-between;}.history time {color:var(--dashboard-muted);font-size:12px;white-space:nowrap;}.history p {color:var(--dashboard-muted);margin:4px 0 0;}.no-results {padding:20px 0;font-size:14px;}
    @media(max-width:650px) {h1 {font-size:28px;}.progress-card {padding:18px;}.progress-filters {gap:14px;}.milestone-toggle {gap:8px;flex-wrap:wrap;}.count {width:100%;margin-left:40px;}.features {margin-left:16px;}.milestone-description {margin-left:16px;}.feature-toggle {gap:8px;}.feature-details {padding-right:0;}.state {font-size:11px;}.history li>div {flex-wrap:wrap;gap:4px;}}
  `})
export class ProgressPageComponent {
  readonly progress=input<ProjectProgress>();
  readonly milestones=computed(()=>this.progress()?.data?.milestones || []);
  readonly count=computed(()=>progressCount(this.progress()));
  readonly filter=signal<'all'|ProgressState>('all');
  readonly filters=[{key:'all',label:'All'},{key:'in_progress',label:'In progress'},{key:'planned',label:'Planned'},{key:'done',label:'Done'}] as const;
  readonly opened=signal(new Map<string,boolean>());
  readonly expanded=signal(new Set<string>());
  readonly visibleMilestones=computed(()=>this.milestones().filter(m=>this.filter()==='all' || m.features.some(f=>f.status===this.filter())));
  filterCount(filter:string) {return this.milestones().flatMap(m=>m.features).filter(f=>filter==='all'||f.status===filter).length;}
  completed(m:ProgressMilestone) {return m.features.filter(f=>f.status==='done').length;}
  milestoneState(m:ProgressMilestone):ProgressState {return m.features.length && this.completed(m)===m.features.length?'done':m.features.some(f=>f.status==='in_progress')?'in_progress':'planned';}
  isOpen(m:ProgressMilestone) {return this.opened().get(m.id) ?? (this.filter()!=='all' || this.milestoneState(m)==='in_progress');}
  toggleMilestone(m:ProgressMilestone) {this.opened.update(value=>new Map(value).set(m.id,!this.isOpen(m)));}
  visibleFeatures(m:ProgressMilestone) {return m.features.filter(f=>this.filter()==='all'||f.status===this.filter());}
  toggleFeature(id:string) {this.expanded.update(value=>{const next=new Set(value);next.has(id)?next.delete(id):next.add(id);return next;});}
  label(status:ProgressState) {return {planned:'Planned',in_progress:'In progress',done:'Done'}[status];}
  icon(status:ProgressState) {return {planned:'bi-circle',in_progress:'bi-circle-half',done:'bi-check-circle'}[status];}
}
