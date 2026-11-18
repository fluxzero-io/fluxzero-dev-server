import {Component, computed, input, output} from '@angular/core';
import {Status, monitoringViews} from './models';

@Component({selector:'dev-monitoring-overview',standalone:true,templateUrl:'./monitoring-overview.component.html',styles:`
  :host{display:block}.page-description{margin:8px 0 0;color:var(--dashboard-muted);line-height:1.6;}
  .service-summary{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,240px),1fr));gap:16px;margin-bottom:24px;}
  .service-card{display:flex;align-items:center;gap:14px;padding:20px;border:1px solid var(--dashboard-border);border-radius:12px;background:var(--dashboard-surface);}
  .service-icon,.view-icon{display:flex;align-items:center;justify-content:center;flex:0 0 44px;width:44px;height:44px;border-radius:10px;background:var(--dashboard-active-soft);color:var(--dashboard-active);font-size:22px;}
  .service-info{flex:1;min-width:0;}.service-card h2{font-size:14px;margin:0 0 6px;}.service-state{font-size:13px;color:var(--dashboard-muted);}.service-state.running{color:var(--dashboard-success-text);}.service-state.failed{color:var(--dashboard-danger-text);}
  .service-version{display:inline-flex;margin-left:6px;padding:1px 5px;border:1px solid var(--dashboard-border);border-radius:4px;font-size:11px;font-weight:400;color:var(--dashboard-muted);}
  .monitoring-note{color:var(--dashboard-muted);font-size:13px;margin:0 0 20px;line-height:1.6;}
  .monitoring-views{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,275px),1fr));gap:16px;}
  .monitoring-card{display:flex;align-items:center;gap:16px;padding:22px;border:1px solid var(--dashboard-border);border-radius:12px;background:var(--dashboard-surface);color:var(--dashboard-text);text-decoration:none;}
  .monitoring-card:hover{background:var(--dashboard-panel-soft);border-color:var(--dashboard-focus-border);text-decoration:none;}.view-copy{flex:1;min-width:0;}.view-copy strong{display:block;font-size:15px;margin-bottom:6px;}.view-copy small{display:block;color:var(--dashboard-muted);font-size:13px;line-height:1.6;}.view-arrow{color:var(--dashboard-control-icon);font-size:16px;}
`})
export class MonitoringOverviewComponent {
  readonly status=input<Status>();
  readonly connected=input(false);
  readonly pageChanged=output<string>();
  readonly services=computed(()=>[
    this.service('testserver','Fluxzero Test Server','hdd-stack',this.status()?.runtime),
    this.service('auditlog','Fluxzero Auditlog','chat-square-text',this.status()?.monitoring.enabled ? this.status()?.monitoring.state : 'disabled'),
  ]);
  readonly views=monitoringViews.filter(view=>view.key!=='visualize').map(view=>({...view,description:{
    messages:'Follow messages and see how your local app handles them.',
    logs:'Read output from your app and local development services.',
    trace:'Follow a request through messages and handlers.',
    issues:'Investigate errors and failed message handling.',
    documents:'Inspect documents indexed by your local app.',
    insights:'Explore local activity and processing performance.',
  }[view.key]}));
  private service(id:string,name:string,icon:string,fallback:string|undefined) {
    const status=this.status(),component=status?.components?.find(c=>c.id===id);
    const stopped=status?.state==='shutdown' || status?.maintenance?.workspaceStopped;
    const state=!this.connected() ? 'disconnected' : stopped ? 'stopped' : component?.state || fallback || 'unknown';
    const label=({running:'Running',starting:'Starting',stopped:'Stopped',failed:'Needs attention',degraded:'Needs attention',disabled:'Not enabled',disconnected:'Disconnected'} as Record<string,string>)[state] || 'Status unknown';
    return {id,name,icon,state,label,version:component?.version};
  }
  navigate(event:MouseEvent,key:string) {
    if(event.button!==0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    event.preventDefault();this.pageChanged.emit('monitoring/'+key);
  }
}
