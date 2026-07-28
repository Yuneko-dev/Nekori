import type { JsCommand, Spec } from '../../specs/NativeHostApi';

export type Handler = (args: unknown) => unknown | Promise<unknown>;

const handlers = new Map<string, Handler>();
const inFlight = new Set<string>();

export function registerHandler(method: string, handler: Handler): void {
  handlers.set(method, handler);
}

/**
 * Resolved lazily, never at module scope.
 *
 * `TurboModuleRegistry.getEnforcing` runs when `specs/NativeHostApi` is first evaluated. Importing it
 * at the top of the bundle means that happens *during* bundle evaluation, before the TurboModule
 * registry is populated, and React Native aborts the process with
 * "[runtime not ready]: 'NativeHostApi' could not be found".
 */
function nativeHostApi(): Spec {
  return require('../../specs/NativeHostApi').default as Spec;
}

async function dispatch(api: Spec, command: JsCommand): Promise<void> {
  const { id, method, args } = command;
  inFlight.add(id);
  try {
    const handler = handlers.get(method);
    if (!handler) {
      // Unknown method must fail loudly. Resolving with undefined would hand Kotlin a plausible
      // "null result" for what is actually a wiring bug.
      throw new Error(`No handler registered for "${method}"`);
    }
    const result = await handler(args === '' ? undefined : JSON.parse(args));
    if (inFlight.has(id)) {
      api.resolve(id, JSON.stringify(result ?? null));
    }
  } catch (error) {
    if (inFlight.has(id)) {
      api.reject(
        id,
        error instanceof Error ? error.message : String(error),
        error instanceof Error ? error.stack ?? '' : '',
      );
    }
  } finally {
    inFlight.delete(id);
  }
}

/**
 * Subscribes to commands and tells Kotlin it is safe to send them.
 *
 * Runs from `setImmediate`, so it executes after the bundle has finished evaluating and the React
 * Native instance is initialised — the earliest point at which the TurboModule can be resolved.
 * Kotlin does not guess at that timing: it waits for `ready()`.
 */
export function startBridge(): void {
  setImmediate(() => {
    try {
      const api = nativeHostApi();
      api.onCommand((command: JsCommand) => {
        // Never let a rejection escape the listener: an unhandled one would leave the Kotlin
        // coroutine suspended forever instead of failing it.
        void dispatch(api, command);
      });
      api.ready();
    } catch (error) {
      // Nothing can be reported through the bridge — this *is* the bridge failing to come up. Kotlin
      // sees it as the ready() timeout, so leave a line saying what actually happened.
      console.error('[tsundoku] bridge bootstrap failed', error);
    }
  });
}
