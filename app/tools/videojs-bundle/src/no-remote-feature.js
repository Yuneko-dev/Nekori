// The @videojs/core selector barrel imports every feature. Keep its remote
// selector inert so that selecting the local controls feature cannot retain
// Remote Playback code.
const disabledFeature = (name) => Object.freeze({ name, state: () => ({}) });

// Selector modules still call createSelector() for these features at module
// initialization, even when the player never includes them. Keep the feature
// shape valid while exposing no state or behavior.
export const remotePlaybackFeature = disabledFeature("remotePlayback-disabled");
export const pipFeature = disabledFeature("pip-disabled");
export const isRemotePlaybackConnected = () => false;
export const isRemotePlaybackConnecting = () => false;
export const requestRemotePlayback = () => Promise.reject(new Error("Remote Playback is disabled"));
export const exitPictureInPicture = () => Promise.resolve();
export const isPictureInPicture = () => false;
export const isPictureInPictureEnabled = () => false;
export const requestPictureInPicture = () => Promise.reject(new Error("Picture-in-Picture is disabled"));
