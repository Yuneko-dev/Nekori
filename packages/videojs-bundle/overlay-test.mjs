import assert from "node:assert/strict";

export async function checkOverlays(page) {
  for (const width of [360, 430, 900]) {
    await page.setViewportSize({ width, height: 800 });
    for (const language of ["en", "vi"]) {
      await page.evaluate(async language => {
        const player = await mediaHarness.setPlayer({ type: "video-file", url: "/direct-long.mp4", progress: 25, language });
        await player.playDirect("/direct-long.mp4");
      }, language);
      await page.waitForFunction(() => !document.querySelector("video-skin")?.shadowRoot.querySelector(".nekori-overlay")?.hidden);
      const inspect = selector => page.evaluate(selector => {
        const shadow = document.querySelector("video-skin").shadowRoot;
        const el = shadow.querySelector(selector);
        const box = el.getBoundingClientRect();
        const player = shadow.querySelector("media-container").getBoundingClientRect();
        const range = document.createRange();
        range.selectNodeContents(el);
        const hit = shadow.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2);
        return {
          inside: box.left >= player.left && box.right <= player.right && box.top >= player.top && box.bottom <= player.bottom,
          clickable: el === hit || el.contains(hit),
          lines: range.getClientRects().length,
        };
      }, selector);
      assert.deepEqual(await inspect('[data-action="continue"]'), { inside: true, clickable: true, lines: 1 }, `${width}/${language}: resume continue`);
      assert.deepEqual(await inspect('[data-action="restart"]'), { inside: true, clickable: true, lines: 1 }, `${width}/${language}: resume restart`);
      assert.equal(await page.locator(".nekori-overlay__title").textContent(), language === "vi" ? "Hệ thống ghi nhận bạn đã từng xem video này trước đó!" : "You watched this video before");
      if (process.env.PLAYER_SCREENSHOTS) await page.screenshot({ path: `${process.env.PLAYER_SCREENSHOTS}/resume-${width}-${language}.png` });
      await page.locator('[data-action="continue"]').click();
      await page.waitForFunction(() => document.querySelector("video")?.currentTime >= 2);

      await page.waitForFunction(() => !document.querySelector("video-skin")?.shadowRoot.querySelector(".nekori-nextup")?.hidden);
      for (const action of ["next", "dismiss"]) {
        assert.deepEqual(await inspect(`[data-action="${action}"]`), { inside: true, clickable: true, lines: 1 }, `${width}/${language}: next ${action}`);
      }
      assert.equal(await page.locator(".nekori-nextup__label").textContent(), language === "vi" ? "Tập tiếp theo" : "Up next");
      if (process.env.PLAYER_SCREENSHOTS) await page.screenshot({ path: `${process.env.PLAYER_SCREENSHOTS}/next-${width}-${language}.png` });
      await page.locator('[data-action="dismiss"]').click();
      assert.equal(await page.locator(".nekori-nextup").isHidden(), true);
    }
    assert.equal(await page.locator('media-time[type="current"]').isVisible(), width !== 360);
  }
  await page.locator("media-fullscreen-button").click();
  await page.waitForFunction(() => !!document.fullscreenElement);
  assert.equal(await page.locator('media-time[type="current"]').isVisible(), true);
  await page.evaluate(() => document.exitFullscreen());
  await page.setViewportSize({ width: 1280, height: 720 });
  console.log("PASS responsive overlays, configured English/Vietnamese labels, timestamp breakpoint");
}
