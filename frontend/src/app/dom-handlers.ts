import {ElementRef} from '@angular/core';

// Same DOM contract as Dashboard's Gateway: bubbling CustomEvent, detail payload,
// $result response and nearest command/query handler wins. No backend fallback.
type Kind = 'command' | 'query' | 'event';
type Method = {name: string; key: string | symbol; kind: Kind};
const methods = new WeakMap<object, Method[]>();
interface ScopedHandler { elementRef: ElementRef<HTMLElement>; }
type ResultEvent<T> = CustomEvent & {$result: T};

function handle(kind: Kind, name?: string): MethodDecorator {
  return (prototype, key) => {
    const own = methods.get(prototype) || [];
    own.push({name: name || String(key), key, kind});
    methods.set(prototype, own);
  };
}
export const HandleCommand = (name?: string) => handle('command', name);
export const HandleQuery = (name?: string) => handle('query', name);
export const HandleEvent = (name?: string) => handle('event', name);

export function Handler(): ClassDecorator {
  return target => {
    const prototype = target.prototype;
    const init = prototype.ngOnInit;
    const destroy = prototype.ngOnDestroy;
    const registrations = new WeakMap<ScopedHandler, () => void>();
    prototype.ngOnInit = function(this: ScopedHandler, ...args: unknown[]) {
      registrations.get(this)?.();
      registrations.set(this, registerHandlers(this));
      init?.apply(this, args);
    };
    prototype.ngOnDestroy = function(this: ScopedHandler, ...args: unknown[]) {
      registrations.get(this)?.();
      registrations.delete(this);
      destroy?.apply(this, args);
    };
  };
}

export function registerHandlers(instance: ScopedHandler): () => void {
  const element = instance.elementRef.nativeElement;
  const names = new Set<string>();
  const cleanup: (() => void)[] = [];
  for (let prototype = Object.getPrototypeOf(instance); prototype; prototype = Object.getPrototypeOf(prototype)) {
    for (const method of methods.get(prototype) || []) {
      if (names.has(method.name)) continue;
      names.add(method.name);
      const listener = (event: Event) => {
        const message = event as ResultEvent<unknown>;
        // A handler failure is returned to the caller, never silently routed to an ancestor.
        if (method.kind !== 'event') event.stopPropagation();
        try {
          message.$result = (instance as any)[method.key](message.detail, message);
        } catch (error) { message.$result = Promise.reject(error); }
      };
      element.addEventListener(method.name, listener);
      cleanup.push(() => element.removeEventListener(method.name, listener));
    }
  }
  return () => cleanup.forEach(remove => remove());
}

function dispatch<T>(element: Element, type: string, payload?: unknown): T {
  const event = new CustomEvent(type, {detail: payload, bubbles: true, cancelable: true}) as ResultEvent<T>;
  element.dispatchEvent(event);
  if (!Object.prototype.hasOwnProperty.call(event, '$result')) throw Error(`No DOM handler for ${type}`);
  return event.$result;
}
export const sendCommand = <T = void>(element: Element, type: string, payload?: unknown): T => dispatch<T>(element, type, payload);
export const sendQuery = <T>(element: Element, type: string, payload?: unknown): T => dispatch<T>(element, type, payload);

export function publishEvent(element: Element, type: string, payload: unknown): void {
  element.dispatchEvent(new CustomEvent(type, {detail: payload, bubbles: true}));
}
