class DisabledAirPlayButtonElement extends HTMLElement {
  static tagName = "media-airplay-button";
}

class DisabledCastButtonElement extends HTMLElement {
  static tagName = "media-cast-button";
}

class DisabledPiPButtonElement extends HTMLElement {
  static tagName = "media-pip-button";
}

export {
  DisabledAirPlayButtonElement as AirPlayButtonElement,
  DisabledCastButtonElement as CastButtonElement,
  DisabledPiPButtonElement as PiPButtonElement,
};
