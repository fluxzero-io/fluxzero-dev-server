import {PreviewNavigation} from './preview-navigation';

describe('Preview navigation',()=>{
  function browser() {
    const location={href:'http://localhost:4242/'};
    const events=new EventTarget();
    const history={
      pushState:(_data:unknown,_title:string,url?:string|URL|null)=>{if(url) location.href=new URL(String(url),location.href).href;},
      replaceState:(_data:unknown,_title:string,url?:string|URL|null)=>{if(url) location.href=new URL(String(url),location.href).href;}
    };
    const frame={src:location.href,contentWindow:{location,history,addEventListener:events.addEventListener.bind(events),removeEventListener:events.removeEventListener.bind(events)}};
    return {frame:frame as unknown as HTMLIFrameElement,location,history,events};
  }
  it('tracks SPA pushes and replacements and keeps back/forward inside the iframe',()=>{
    const {frame,location,history}=browser();const nav=new PreviewNavigation();const root=location.href;
    const originalPush=history.pushState;nav.connect(frame,root);
    expect(nav.canBack()).toBeFalse();history.pushState({},'','/events/1');
    expect(nav.canBack()).toBeTrue();expect(nav.url()).toBe(root+'events/1');
    history.replaceState({},'','/events/2');nav.go(-1);
    expect(frame.src).toBe(root);expect(nav.canForward()).toBeTrue();
    location.href=frame.src;nav.connect(frame,root);nav.go(1);
    expect(frame.src).toBe(root+'events/2');location.href=frame.src;nav.connect(frame,root);
    nav.refresh(root);expect(frame.src).toBe(root+'events/2');
    nav.dispose();expect(history.pushState).toBe(originalPush);
  });
  it('drops forward entries after a new navigation and resets for a different app',()=>{
    const {frame,location,history}=browser();const nav=new PreviewNavigation();const root=location.href;nav.connect(frame,root);
    history.pushState({},'','/a');history.pushState({},'','/b');nav.go(-1);location.href=frame.src;nav.connect(frame,root);
    history.pushState({},'','/c');expect(nav.canForward()).toBeFalse();
    location.href='http://localhost:4242/other';nav.connect(frame,location.href);expect(nav.canBack()).toBeFalse();
    nav.dispose();
  });
  it('switches mounted frontends in the same frame and keeps navigation history',()=>{
    const {frame,location,history}=browser();const nav=new PreviewNavigation();const root=location.href;nav.connect(frame,root);
    nav.navigate(root+'inbox');location.href=frame.src;nav.connect(frame,root);
    history.pushState({},'','/inbox/messages/1');expect(nav.url()).toBe(root+'inbox/messages/1');
    nav.go(-1);expect(frame.src).toBe(root+'inbox');location.href=frame.src;nav.connect(frame,root);
    nav.go(-1);expect(frame.src).toBe(root);location.href=frame.src;nav.connect(frame,root);
    nav.go(1);expect(frame.src).toBe(root+'inbox');nav.dispose();
  });
  it('retains explicit public ingress navigation when the iframe is cross origin',()=>{
    const {frame}=browser();const nav=new PreviewNavigation();const root='https://app.local:8443/';frame.src=root;
    Object.defineProperty(frame.contentWindow,'location',{get:()=>{throw new Error('cross origin');},configurable:true});
    nav.connect(frame,root,true);expect(nav.url()).toBe(root);expect(nav.canBack()).toBeFalse();
    nav.navigate(root+'inbox');nav.connect(frame,root,true);expect(nav.url()).toBe(root+'inbox');
    nav.go(-1);expect(frame.src).toBe(root);nav.connect(frame,root,true);
    nav.go(1);expect(frame.src).toBe(root+'inbox');nav.connect(frame,root,true);
    nav.refresh(root);expect(frame.src).toBe(root+'inbox');nav.dispose();
  });
  it('keeps a frontend selected before the first cross-origin load',()=>{
    const {frame}=browser();const nav=new PreviewNavigation();const root='https://app.local:8443/';frame.src=root+'inbox';
    Object.defineProperty(frame.contentWindow,'location',{get:()=>{throw new Error('cross origin');},configurable:true});
    nav.connect(frame,root,true);expect(nav.url()).toBe(root+'inbox');expect(nav.canBack()).toBeFalse();
    nav.refresh(root);expect(frame.src).toBe(root+'inbox');nav.dispose();
  });
  it('returns from an inaccessible frame without traversing the dashboard',()=>{
    const {frame,location}=browser();const nav=new PreviewNavigation();const root=location.href;nav.connect(frame,root);
    Object.defineProperty(frame.contentWindow,'location',{get:()=>{throw new Error('cross origin');},configurable:true});
    nav.connect(frame,root);expect(nav.canBack()).toBeTrue();nav.go(-1);expect(frame.src).toBe(root);nav.dispose();
  });
});
