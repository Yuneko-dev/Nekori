import type { CodegenTypes, TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

/** One unit of work for JavaScript. `args` is JSON, so the payload crosses as a single string. */
export type JsCommand = {
  id: string;
  method: string;
  args: string;
};

/**
 * The whole native <-> JS contract, in one typed place.
 *
 * Kotlin -> JS is `onCommand`, a codegen-generated event emitter rather than a global device event:
 * the channel belongs to this module instead of sharing `DeviceEventEmitter` with everything else in
 * the process, and codegen keeps both ends of the payload type in sync.
 *
 * JS -> Kotlin is `resolve`/`reject`, keyed by the command id, plus `ready` — which exists because
 * "the React Native instance started" is not the same as "JavaScript has subscribed". Without the
 * handshake a command emitted in that window is delivered to nobody and the Kotlin caller suspends
 * forever.
 */
export interface Spec extends TurboModule {
  /** Called once, after the JS side has subscribed to [onCommand]. */
  ready(): void;

  /** Completes the pending Kotlin coroutine for `id` with a JSON payload. */
  resolve(id: string, json: string): void;

  /** Fails the pending Kotlin coroutine for `id`. */
  reject(id: string, message: string): void;

  /** Cryptographically secure bytes for the Web Crypto getRandomValues polyfill. */
  getRandomBase64(byteLength: number): string;

  loadPluginStorage(pluginId: string): Promise<string>;

  applyPluginStorageMutation(pluginId: string, mutationJson: string): Promise<void>;

  readonly onCommand: CodegenTypes.EventEmitter<JsCommand>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('NativeHostApi');
