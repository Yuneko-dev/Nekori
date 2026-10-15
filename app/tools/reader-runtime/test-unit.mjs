import { spawnSync } from 'node:child_process';
import { readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const packageDir = fileURLToPath(new URL('.', import.meta.url));
const tests = ['test/', '../../src/test/js/'].flatMap((directory) =>
  readdirSync(new URL(directory, import.meta.url))
    .filter((file) => file.endsWith('.test.mjs') && !file.endsWith('.browser.test.mjs'))
    .map((file) => fileURLToPath(new URL(directory + file, import.meta.url))),
);
if (!tests.length) throw new Error('No reader Node tests found');
const result = spawnSync(process.execPath, ['--test', ...tests], {
  cwd: packageDir,
  stdio: 'inherit',
});
if (result.error) throw result.error;
process.exit(result.status ?? 1);
