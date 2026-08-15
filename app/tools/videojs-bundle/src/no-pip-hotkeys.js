export const isHotkeyToggleAction = (action) => action.startsWith("toggle");

const actions = {
  togglePaused: ({ store }) => (store.state.paused ? store.state.play?.() : store.state.pause?.()),
  toggleMuted: ({ store }) => store.state.toggleMuted?.(),
  toggleFullscreen: ({ store }) =>
    store.state.fullscreen ? store.state.exitFullscreen?.() : store.state.requestFullscreen?.(),
  toggleSubtitles: ({ store }) => store.state.toggleSubtitles?.(),
  seekStep: ({ store, value }) => {
    if (value !== undefined) store.state.seek?.(store.state.currentTime + value);
  },
  volumeStep: ({ store, value }) => {
    if (value !== undefined) store.state.setVolume?.(store.state.volume + value);
  },
  speedUp: ({ store }) => changeSpeed(store, 1),
  speedDown: ({ store }) => changeSpeed(store, -1),
  seekToPercent: ({ store, value, key }) => {
    const percent = value !== undefined ? value : key >= "0" && key <= "9" ? Number(key) * 10 : undefined;
    if (percent !== undefined && store.state.duration > 0) {
      store.state.seek?.((percent / 100) * store.state.duration);
    }
  },
};

function changeSpeed(store, direction) {
  const rates = store.state.playbackRates;
  if (!rates?.length) return;
  const current = rates.indexOf(store.state.playbackRate);
  const next = direction > 0
    ? current < 0 || current >= rates.length - 1 ? 0 : current + 1
    : current <= 0 ? rates.length - 1 : current - 1;
  store.state.setPlaybackRate?.(rates[next]);
}

export const resolveHotkeyAction = (name) => actions[name];
