// !todo: implement cloudflare-cdp support

class UnsupportedPluginCapabilityError extends Error {
  readonly code = "UNSUPPORTED_CAPABILITY";

  constructor(readonly capability: string) {
    super(`Plugin capability "${capability}" is not supported yet`);
    this.name = "UnsupportedPluginCapabilityError";
  }
}

function unsupportedCloudflare(): never {
  throw new UnsupportedPluginCapabilityError("cloudflare-cdp");
}

export const solveCloudflareAPI = unsupportedCloudflare;
export const solveCloudflareTurnstileAPI = unsupportedCloudflare;
