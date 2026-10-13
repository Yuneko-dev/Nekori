export const PLAYER_TAGS = {
  live: { player: "live-video-player", skin: "live-video-skin" },
  vod: { player: "video-player", skin: "video-skin" },
} as const;
export const ELEMENT_DEFINE_TIMEOUT_MS = 10_000;

export async function awaitPlayerElements(
  registry: CustomElementRegistry,
  tags: readonly string[],
  timeoutMs = ELEMENT_DEFINE_TIMEOUT_MS,
): Promise<void> {
  const pending = tags.filter((tag) => tag !== "video" && !registry.get(tag));
  if (pending.length === 0) return;
  let timeout: ReturnType<typeof setTimeout> | undefined;
  try {
    await Promise.race([
      Promise.all(pending.map((tag) => registry.whenDefined(tag))),
      new Promise<never>((_, reject) => {
        timeout = setTimeout(() => reject(new Error(`custom element ${pending.join(", ")} is unavailable`)), timeoutMs);
      }),
    ]);
  } finally {
    if (timeout !== undefined) clearTimeout(timeout);
  }
}
