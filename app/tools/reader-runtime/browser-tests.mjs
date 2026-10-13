import { existsSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { chromium } from 'playwright';

const packageDir = path.dirname(fileURLToPath(import.meta.url));
const candidates = [
  process.env.CHROMIUM_PATH,
  chromium.executablePath(),
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
].filter(Boolean);
const executable = candidates.find(existsSync);
if (!executable) throw new Error('Reader browser tests require Chromium. Run npx playwright install chromium or set CHROMIUM_PATH.');
const tests = readdirSync(path.join(packageDir, 'test'))
  .filter((file) => file.endsWith('.browser.test.mjs'))
  .map((file) => path.join(packageDir, 'test', file));
if (!tests.length) throw new Error('No reader browser acceptance tests found');
const result = spawnSync(process.execPath, ['--test', ...tests], {
  cwd: packageDir,
  stdio: 'inherit',
  env: {
    ...process.env,
    CHROMIUM_PATH: executable,
    PLAYWRIGHT_MODULE: path.join(packageDir, 'node_modules', 'playwright'),
    READER_BROWSER_REQUIRED: '1',
  },
});
if (result.error) throw result.error;
process.exit(result.status ?? 1);
