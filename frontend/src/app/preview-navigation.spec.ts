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
  it('returns from an inaccessible frame without traversing the dashboard',()=>{
    const {frame,location}=browser();const nav=new PreviewNavigation();const root=location.href;nav.connect(frame,root);
    Object.defineProperty(frame.contentWindow,'location',{get:()=>{throw new Error('cross origin');},configurable:true});
    nav.connect(frame,root);expect(nav.canBack()).toBeTrue();nav.go(-1);expect(frame.src).toBe(root);nav.dispose();
  });
});
