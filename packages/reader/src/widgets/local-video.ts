import type { ReaderHost, Unsubscribe } from '../protocol/reader-api.ts';

/** The host opens downloaded video files; the page owns only its replay control. */
export function installLocalVideoControl(host: ReaderHost): Unsubscribe {
  const root = host.root;
  const onClick = (event: MouseEvent): void => {
    const target = event.target instanceof Element ? event.target : null;
    if (!target?.closest('button#nekori-play-local-video')) return;
    event.preventDefault();
    event.stopPropagation();
    host.emit('media.playLocalVideo', {});
  };
  root.addEventListener('click', onClick);
  return () => root.removeEventListener('click', onClick);
}
