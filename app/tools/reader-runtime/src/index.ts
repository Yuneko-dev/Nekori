export { createReader } from './runtime/create-reader.ts';
export type { CreateReaderOptions } from './runtime/create-reader.ts';
export type { DashConfig, PlayerConfig, SubtitleTrack, VideoDirective, VideoType } from './media/types.ts';
export type { LayoutConfig, ScrollTrackingConfig } from './layout/types.ts';
export { createRuntimeHost } from './runtime/host.ts';
export type { CreateRuntimeHostOptions } from './runtime/host.ts';
export type { NativeReaderTransport } from './runtime/session.ts';

export type {
  ReaderChapter,
  ReaderConfig,
  ReaderContext,
  ReaderNovel,
  ReaderRuntimeState,
} from './protocol/reader-config.ts';
export { parseReaderConfig, readReaderConfig, ReaderConfigError } from './protocol/reader-config.ts';

export type {
  ReaderApi,
  ReaderCommandHandler,
  ReaderCommandName,
  ReaderEventBus,
  ReaderEventMap,
  ReaderEventName,
  ReaderHost,
  ReaderLayout,
  ReaderPlayer,
  ReaderRequestName,
  Unsubscribe,
} from './protocol/reader-api.ts';

export type {
  ReaderCancelEnvelope,
  ReaderCommandEnvelope,
  ReaderEnvelope,
  ReaderErrorPayload,
  ReaderEventEnvelope,
  ReaderLifecycleEnvelope,
  ReaderMessageKind,
  ReaderResultEnvelope,
} from './protocol/envelope.ts';
export { parseEnvelope, ReaderProtocolError, READER_PROTOCOL_VERSION } from './protocol/envelope.ts';
import type { ReaderApi } from './protocol/reader-api.ts';

declare global {
  interface Window {
    /** Available during reader boot and removed when its document is disposed. */
    nekori?: ReaderApi;
  }
}

