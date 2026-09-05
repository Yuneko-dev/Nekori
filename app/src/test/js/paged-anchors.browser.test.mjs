import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import test from "node:test";

try {
    // Run with Playwright available on NODE_PATH; CHROMIUM_PATH can select an installed Chromium.
    const { chromium } = createRequire(import.meta.url)("playwright");
    const asset = (name) =>
        readFileSync(
            new URL(`../../main/assets/novel-reader/${name}`, import.meta.url),
            "utf8",
        );
    if (!process.env.CHROMIUM_PATH || !chromium) throw new Error("Playwright not available, skipping browser tests");

    test("vertical fragments resolve duplicate ids inside the clicked chapter", async () => {
        const browser = await chromium.launch({
            executablePath: process.env.CHROMIUM_PATH,
        });
        try {
            const page = await browser.newPage({
                viewport: { width: 384, height: 832 },
            });
            await page.setContent(`<div id="LNReader-chapter">
            <tsundoku-chapter data-chapter-id="1"><p id="note">Wrong chapter</p></tsundoku-chapter>
            <div style="height: 1000px"></div>
            <tsundoku-chapter data-chapter-id="2"><a href="#note">Footnote</a>
                <div style="height: 1000px"></div><p id="note">Correct chapter</p>
                <div style="height: 1000px"></div>
            </tsundoku-chapter></div>`);
            await page.addScriptTag({ content: asset("scoped-chapter-anchors.js") });
            await page.evaluate(() => document.querySelector("a").click());
            const position = await page.evaluate(() => ({
                targetTop: document
                    .querySelector('[data-chapter-id="2"] [id="note"]')
                    .getBoundingClientRect().top,
                scrollY: window.scrollY,
                hash: location.hash,
            }));
            assert.ok(Math.abs(position.targetTop) <= 1, JSON.stringify(position));
            assert.ok(position.scrollY > 1000, JSON.stringify(position));
            assert.equal(position.hash, "");
        } finally {
            await browser.close();
        }
    });

    test("fragment round trips keep the paged viewport aligned", async (t) => {
        const browser = await chromium.launch({
            executablePath: process.env.CHROMIUM_PATH,
        });
        try {
            for (const direction of ["ltr", "rtl"])
                for (const spread of ["single", "double"]) {
                    await t.test(`${direction} ${spread}`, async () => {
                        const page = await browser.newPage({
                            viewport: { width: 384, height: 832 },
                        });
                        await page.setContent(`<style>
                    :root { --reader-margin-top: 30px; --reader-margin-bottom: 20px;
                        --reader-margin-left: 20px; --reader-margin-right: 20px;
                        --reader-font-size: 18px; --reader-line-height: 1.6; }
                    ${asset("reader.css")}
                </style><body><div id="LNReader-chapter"><tsundoku-chapter data-chapter-id="1">
                    <div style="overflow: hidden"><p id="back">Opening paragraph <a href="#note">[note]</a></p>
                    ${"<p>A paragraph long enough to fill several lines in this narrow reader viewport.</p>".repeat(45)}
                    </div>${"<p>More flowing text after the source block, before the footnote.</p>".repeat(20)}
                    <div><p id="note"><a href="#back">[back]</a> <a href="#">[top]</a> Footnote</p></div>
                    <tsundoku-chapter-summary>Summary</tsundoku-chapter-summary>
                </tsundoku-chapter></div></body>`);
                        await page.addScriptTag({
                            content: asset("reader-layout.js").replaceAll(
                                "__TSUNDOKU_OBJECT_NAME__",
                                "Tsundoku",
                            ),
                        });
                        await page.addScriptTag({ content: asset("page-reader.js") });
                        await page.addScriptTag({
                            content: asset("scoped-chapter-anchors.js"),
                        });
                        await page.addScriptTag({ content: asset("chapter-summary.js") });
                        await page.evaluate(
                            ({ direction, spread }) =>
                                window.Tsundoku.runtime.readerLayout.configure({
                                    enabled: true,
                                    spread,
                                    direction,
                                    chapterId: "1",
                                }),
                            { direction, spread },
                        );
                        await page.evaluate(() => new Promise(requestAnimationFrame));
                        await page.waitForTimeout(250);
                        for (const href of ["#note", "#back", "#note", "#", "summary"]) {
                            // DOM click avoids Playwright's own scroll-into-view altering the reader viewport.
                            await page.evaluate((href) => {
                                if (href === "summary") window.__tsundokuSummary.focus("1");
                                else document.querySelector(`a[href="${href}"]`).click();
                            }, href);
                            await page.waitForTimeout(250);
                            const position = await page.evaluate(() => {
                                const root = document.getElementById("LNReader-chapter");
                                return {
                                    top: root.scrollTop,
                                    documentTop: window.scrollY,
                                    left: root.scrollLeft,
                                    width: root.clientWidth,
                                    scrollWidth: root.scrollWidth,
                                };
                            });
                            assert.equal(
                                position.top,
                                0,
                                `${href}: column viewport must not scroll vertically`,
                            );
                            assert.equal(
                                position.documentTop,
                                0,
                                `${href}: document must not scroll vertically`,
                            );
                            assert.equal(
                                Math.abs(position.left) % position.width,
                                0,
                                `${href}: complete page alignment`,
                            );
                            if (href === "#back" || href === "#")
                                assert.equal(position.left, 0);
                            else
                                assert.ok(
                                    direction === "rtl" ? position.left < 0 : position.left > 0,
                                    JSON.stringify(position),
                                );
                        }
                        await page.close();
                    });
                }
        } finally {
            await browser.close();
        }
    });
} catch (e) {
    console.error(e);
}
