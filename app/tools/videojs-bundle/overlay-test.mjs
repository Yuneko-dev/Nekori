import assert from "node:assert/strict";

export async function checkOverlays(page) {
  for (const width of [360, 430, 900]) {
    await page.setViewportSize({ width, height: 800 });
    for (const language of ["en", "vi"]) {
      await page.evaluate(language => {
        const p = LNReaderPlayer;
        p.videoElement.pause();
        const vi = language === "vi";
        const text = (root, selector, value) => root.querySelector(selector).textContent = value;
        text(p.resumeOverlay, ".lnreader-overlay__title", vi ? "Hệ thống ghi nhận bạn đã từng xem video này trước đó!" : "You watched this video before");
        text(p.resumeOverlay, ".lnreader-overlay__question", vi ? "Bạn có muốn xem tiếp từ đoạn" : "Continue from");
        text(p.resumeOverlay, '[data-action="continue"]', vi ? "Xem tiếp" : "Continue");
        text(p.resumeOverlay, '[data-action="restart"]', vi ? "Từ đầu" : "From the beginning");
        text(p.nextUpPopup, ".lnreader-nextup__label", vi ? "Tập tiếp theo" : "Up next");
        text(p.nextUpPopup, '[data-action="next"]', vi ? "Xem ngay" : "Play now");
        p.offerResume(p.videoElement, 2);
      }, language);
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
      for (const action of ["continue", "restart"]) {
        assert.deepEqual(await inspect(`[data-action="${action}"]`), { inside: true, clickable: true, lines: 1 }, `${width}/${language}: resume ${action}`);
      }
      if (process.env.PLAYER_SCREENSHOTS) await page.screenshot({ path: `${process.env.PLAYER_SCREENSHOTS}/resume-${width}-${language}.png` });
      await page.locator('[data-action="continue"]').click();
      await page.evaluate(() => LNReaderPlayer.videoElement.play());
      await page.evaluate(() => {
        LNReaderPlayer.videoElement.pause();
        LNReaderPlayer.updateNextUp({ duration: 300, currentTime: 233 });
      });
      for (const action of ["next", "dismiss"]) {
        assert.deepEqual(await inspect(`[data-action="${action}"]`), { inside: true, clickable: true, lines: 1 }, `${width}/${language}: next ${action}`);
      }
      if (process.env.PLAYER_SCREENSHOTS) await page.screenshot({ path: `${process.env.PLAYER_SCREENSHOTS}/next-${width}-${language}.png` });
      await page.locator('[data-action="dismiss"]').click();
      assert.equal(await page.evaluate(() => LNReaderPlayer.nextUpPopup.hidden), true);
      await page.evaluate(() => { LNReaderPlayer.nextUpDismissed = false; });
    }
    // RC2 deliberately hides timestamps when the time-slider group is narrower than 16rem.
    assert.equal(await page.locator('media-time[type="current"]').isVisible(), width !== 360);
  }
  await page.locator("media-fullscreen-button").click();
  await page.waitForFunction(() => !!document.fullscreenElement);
  await page.evaluate(() => LNReaderPlayer.offerResume(LNReaderPlayer.videoElement, 2));
  await page.locator('[data-action="continue"]').click();
  assert.equal(await page.locator('media-time[type="current"]').isVisible(), true);
  await page.evaluate(() => document.exitFullscreen());
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.evaluate(() => LNReaderPlayer.videoElement.play());
  console.log("PASS mobile/desktop overlays, English/Vietnamese labels, timestamp breakpoint");
}
