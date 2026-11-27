import {TestBed} from '@angular/core/testing';
import {ConsoleConnection, CONSOLE_SOCKET, ConsoleState} from './console-connection';

class Socket {
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
  onmessage: ((event: {data: string}) => void) | null = null;
  closed = false;
  close() {this.closed = true;}
  receive(value: unknown) {this.onmessage?.({data:JSON.stringify(value)});}
}
describe('Console push connection', () => {
  let service: ConsoleConnection;
  let sockets: Socket[];
  let updates: ConsoleState[];
  let states: boolean[];
  const sample={at:5000,applicationMemory:1,devserverMemory:2,monitoringStorage:3,componentMemory:{devserver:2,storage:null}};
  const snapshot=(sequence=1) => ({type:'snapshot',version:1,sequence,status:{projectDirectory:'/customer',resourceHistory:[sample],monitoring:{enabled:false}},environments:[]});
  beforeEach(() => {
    jasmine.clock().install(); sockets=[];updates=[];states=[];
    TestBed.configureTestingModule({providers:[{provide:CONSOLE_SOCKET,useValue:()=>{const socket=new Socket();sockets.push(socket);return socket;}}]});
    service=TestBed.inject(ConsoleConnection);
    service.initialise(state=>updates.push(state),value=>states.push(value));
  });
  afterEach(()=>{service.close();jasmine.clock().uninstall();});
  it('hydrates history once, applies deltas and ignores duplicate frames',()=>{
    sockets[0].receive(snapshot());
    sockets[0].receive({type:'update',version:1,sequence:2,status:{tests:'running'},sample:{...sample,at:10000}});
    sockets[0].receive({type:'update',version:1,sequence:2,status:{tests:'stale'}});
    sockets[0].receive({type:'heartbeat',version:1,sequence:2});
    expect(updates.length).toBe(2);
    expect(updates[1].status.tests).toBe('running');
    expect(updates[1].status.resourceHistory?.map(s=>s.at)).toEqual([5000,10000]);
    expect(states.at(-1)).toBeTrue();
  });
  it('appends output deltas and evicts lines outside the server tail',()=>{
    sockets[0].receive({...snapshot(),status:{...snapshot().status,testOutput:[{sequence:1,module:'a',text:'first'}]}});
    sockets[0].receive({type:'update',version:1,sequence:2,output:{firstSequence:1,lines:[{sequence:2,module:'a',text:'second'}]}});
    expect(updates.at(-1)?.status.testOutput?.map(line=>line.text)).toEqual(['first','second']);
    sockets[0].receive({type:'update',version:1,sequence:3,output:{firstSequence:2,lines:[{sequence:3,module:'a',text:'third'}]}});
    expect(updates.at(-1)?.status.testOutput?.map(line=>line.text)).toEqual(['second','third']);
    sockets[0].receive({type:'update',version:1,sequence:4,output:{firstSequence:0,lines:[]}});
    expect(updates.at(-1)?.status.testOutput).toEqual([]);
    sockets[0].receive({type:'update',version:1,sequence:5,output:{firstSequence:4,lines:[{sequence:4,module:'a',text:'new output'}]}});
    expect(updates.at(-1)?.status.testOutput?.map(line=>line.text)).toEqual(['new output']);
  });
  it('reconnects after a sequence gap, requires a snapshot, and replaces previous history',()=>{
    sockets[0].receive(snapshot());
    sockets[0].receive({type:'update',version:1,sequence:3});
    expect(sockets[0].closed).toBeTrue();expect(states.at(-1)).toBeFalse();
    jasmine.clock().tick(1000);
    expect(sockets.length).toBe(2);
    sockets[1].receive({...snapshot(8),status:{...snapshot().status,resourceHistory:[{...sample,at:40000}]}});
    expect(updates.at(-1)?.status.resourceHistory?.map(s=>s.at)).toEqual([40000]);
  });
  it('bounds reconnect backoff and tears down retries and a silent socket',()=>{
    sockets[0].onerror?.();jasmine.clock().tick(999);expect(sockets.length).toBe(1);
    jasmine.clock().tick(1);expect(sockets.length).toBe(2);
    sockets[1].onerror?.();jasmine.clock().tick(1999);expect(sockets.length).toBe(2);
    jasmine.clock().tick(1);expect(sockets.length).toBe(3);
    sockets[2].receive(snapshot());jasmine.clock().tick(30000);expect(sockets[2].closed).toBeTrue();
    service.close();jasmine.clock().tick(120000);expect(sockets.length).toBe(3);
  });
  it('does not apply deltas before a snapshot or accept an unsupported protocol',()=>{
    sockets[0].receive({type:'update',version:1,sequence:1});
    expect(updates.length).toBe(0);expect(sockets[0].closed).toBeTrue();
    jasmine.clock().tick(1000);sockets[1].receive({...snapshot(),version:2});
    expect(sockets[1].closed).toBeTrue();expect(updates.length).toBe(0);
  });
  it('retries immediately on network recovery and ignores wake events for a fresh connection or closed client',()=>{
    sockets[0].receive(snapshot());
    expect(service.connecting()).toBeFalse();
    window.dispatchEvent(new Event('online'));expect(sockets.length).toBe(1);
    sockets[0].onerror?.();expect(service.connecting()).toBeFalse();
    window.dispatchEvent(new Event('online'));
    expect(sockets.length).toBe(2);expect(service.connecting()).toBeTrue();
    sockets[1].receive(snapshot(9));expect(service.connecting()).toBeFalse();
    jasmine.clock().tick(1000);expect(sockets.length).toBe(2);
    service.close();
    window.dispatchEvent(new Event('focus'));window.dispatchEvent(new Event('online'));document.dispatchEvent(new Event('visibilitychange'));
    jasmine.clock().tick(60000);expect(sockets.length).toBe(2);
  });
  it('replaces a stale socket on browser resume before suspended watchdog timers have run',()=>{
    sockets[0].receive(snapshot());
    jasmine.clock().mockDate(new Date(Date.now()+31000));
    window.dispatchEvent(new Event('focus'));
    expect(sockets[0].closed).toBeTrue();expect(sockets.length).toBe(2);
    sockets[1].receive(snapshot(10));expect(states.at(-1)).toBeTrue();
  });
  it('cancels pending retries and stale callbacks when the user explicitly reinitialises the connection',()=>{
    sockets[0].receive(snapshot());
    const stale=sockets[0].onmessage!;
    sockets[0].onerror?.();
    service.initialise(state=>updates.push(state),value=>states.push(value));
    expect(sockets.length).toBe(2);
    stale({data:JSON.stringify(snapshot(99))});expect(updates.length).toBe(1);
    sockets[1].receive(snapshot(2));expect(updates.length).toBe(2);
    jasmine.clock().tick(1000);expect(sockets.length).toBe(2);
  });
});
