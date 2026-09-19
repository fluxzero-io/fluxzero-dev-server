import {signal} from '@angular/core';

/** Own URL history: iframe history.back() would also traverse dashboard navigation. */
export class PreviewNavigation {
  readonly url=signal(''); readonly canBack=signal(false); readonly canForward=signal(false);
  private entries:string[]=[]; private index=-1; private root=''; private navigating=false;
  private external=false;
  private frame?:HTMLIFrameElement; private detach=()=>{};
  connect(frame:HTMLIFrameElement, root:string) {
    this.detach();this.frame=frame;
    if(root!==this.root) {this.root=root;this.entries=[];this.index=-1;this.navigating=false;this.url.set(root);}
    try {
      const win=frame.contentWindow!;
      if(!/^https?:/.test(win.location.href)) return;
      this.external=false;this.record(win.location.href,this.navigating);this.navigating=false;
      const history=win.history;const push=history.pushState;const replace=history.replaceState;
      const observe=(replaceEntry=false)=>this.record(win.location.href,replaceEntry);
      const pushed:History['pushState']=function(...args) {push.apply(history,args);observe();};
      const replaced:History['replaceState']=function(...args) {replace.apply(history,args);observe(true);};
      history.pushState=pushed;history.replaceState=replaced;
      const changed=()=>observe();win.addEventListener('popstate',changed);win.addEventListener('hashchange',changed);
      this.detach=()=>{
        try {if(history.pushState===pushed) history.pushState=push;if(history.replaceState===replaced) history.replaceState=replace;} catch {}
        win.removeEventListener('popstate',changed);win.removeEventListener('hashchange',changed);
      };
    } catch {
      // An external login page cannot expose its URL/history to the dashboard.
      this.external=true;this.url.set(this.root);this.canBack.set(this.index>=0);this.canForward.set(false);
    }
  }
  private record(url:string, replace=false) {
    if(url===this.entries[this.index]) {this.url.set(url);this.update();return;}
    if(replace && this.index>=0) this.entries[this.index]=url;
    else {this.entries=this.entries.slice(0,this.index+1);this.entries.push(url);this.index++;}
    this.url.set(url);this.update();
  }
  private update() {this.canBack.set(this.index>0);this.canForward.set(this.index<this.entries.length-1);}
  go(direction:-1|1) {
    const next=this.external && direction===-1 ? this.index : this.index+direction;
    if(!this.frame || next<0 || next>=this.entries.length) return;
    this.external=false;this.index=next;this.navigating=true;this.url.set(this.entries[next]);this.update();
    this.frame.src=this.entries[next];
  }
  refresh(root:string) {
    if(!this.frame) return;
    this.navigating=true;this.frame.src=this.url() || root;
  }
  dispose() {this.detach();this.detach=()=>{};}
}
