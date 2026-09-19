import {ElementRef} from '@angular/core';
import {Handler, HandleCommand, HandleQuery, sendCommand, sendQuery} from './dom-handlers';

describe('Dashboard DOM command/query contract', () => {
  @Handler()
  class Parent {
    count = 0;
    constructor(public elementRef: ElementRef<HTMLElement>) {}
    ngOnInit() {}
    ngOnDestroy() {}
    @HandleQuery('context') context() { return 'parent'; }
    @HandleCommand('increment') increment(by: number) { this.count += by; }
  }
  @Handler()
  class Child {
    constructor(public elementRef: ElementRef<HTMLElement>) {}
    ngOnInit() {}
    ngOnDestroy() {}
    @HandleQuery('context') context() { return 'child'; }
    @HandleQuery('async') async answer(value: number) { return value * 2; }
  }
  it('uses the nearest ancestor and returns responses from that scope only', async () => {
    const root = document.createElement('div'), child = document.createElement('div'), leaf = document.createElement('button');
    root.appendChild(child); child.appendChild(leaf);
    const parentHandler = new Parent(new ElementRef(root)), childHandler = new Child(new ElementRef(child));
    parentHandler.ngOnInit(); childHandler.ngOnInit();
    expect(sendQuery(leaf, 'context')).toBe('child');
    expect(await sendQuery<Promise<number>>(leaf, 'async', 4)).toBe(8);
    sendCommand(leaf, 'increment', 3);
    expect(parentHandler.count).toBe(3);
    childHandler.ngOnDestroy();
    expect(sendQuery(leaf, 'context')).toBe('parent');
    parentHandler.ngOnDestroy();
    expect(() => sendQuery(leaf, 'context')).toThrowError('No DOM handler for context');
  });
  it('does not route commands to sibling scopes or register twice on remount', () => {
    const root = document.createElement('div'), sibling = document.createElement('div');
    const handler = new Parent(new ElementRef(root));
    handler.ngOnInit(); handler.ngOnInit();
    expect(() => sendCommand(sibling, 'increment', 2)).toThrow();
    sendCommand(root, 'increment', 2);
    expect(handler.count).toBe(2);
    handler.ngOnDestroy();
  });
});
