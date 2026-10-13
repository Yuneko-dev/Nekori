import { createRequire } from "node:module";

const required = process.env.READER_BROWSER_REQUIRED === "1";
const chromiumPath = process.env.CHROMIUM_PATH;
let chromium;
let playwrightError;

try {
    const require = createRequire(import.meta.url);
    const playwright = process.env.PLAYWRIGHT_MODULE
        ? require(process.env.PLAYWRIGHT_MODULE)
        : require("playwright");
    chromium = playwright.chromium;
} catch (error) {
    playwrightError = error;
}

const setupProblem = !chromiumPath
    ? "CHROMIUM_PATH is unset"
    : playwrightError
      ? `Playwright could not be loaded: ${playwrightError.message}`
      : !chromium
        ? "Playwright does not export chromium"
        : null;

if (required && setupProblem) {
    throw new Error(
        `Required reader browser tests cannot start: ${setupProblem}. ` +
            "Set CHROMIUM_PATH and provide Playwright (or set PLAYWRIGHT_MODULE).",
        { cause: playwrightError },
    );
}

export { chromium, chromiumPath, setupProblem };

// Local runs may omit browser tooling; the required CI/acceptance mode must set
// READER_BROWSER_REQUIRED=1 and will fail during module loading if setup is absent.
export function browserTest(test, name, optionsOrFn, maybeFn) {
    const options = typeof optionsOrFn === "function" ? undefined : optionsOrFn;
    const fn = typeof optionsOrFn === "function" ? optionsOrFn : maybeFn;
    if (setupProblem) {
        return test(name, { ...options, skip: `optional browser test: ${setupProblem}` }, fn);
    }
    return maybeFn ? test(name, optionsOrFn, maybeFn) : test(name, optionsOrFn);
}
