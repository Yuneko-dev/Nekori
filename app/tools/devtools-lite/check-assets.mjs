import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const assets = path.resolve(process.argv[2] || path.join(root, 'app/build/generated/assets/devtools/devtools-lite'));
const manifest = JSON.parse(fs.readFileSync(path.join(assets, 'manifest.json'), 'utf8'));
assert.ok(!Object.keys(manifest.files).some(file =>
  /^front_end\/(emulated_devices|models\/issues_manager\/descriptions)\//.test(file)),
  'Device emulation assets and Chromium Issues descriptions must not be shipped');
for (const [file, hash] of Object.entries(manifest.files)) {
  const resolved = path.resolve(assets, file);
  assert.ok(resolved.startsWith(assets + path.sep));
  assert.equal(createHash('sha256').update(fs.readFileSync(resolved)).digest('hex'), hash, file);
}
assert.deepEqual(fs.readdirSync(path.join(assets, 'front_end/core/i18n/locales')), ['en-US.json']);
for (const file of ['target.js']) {
  new vm.Script(fs.readFileSync(path.join(root, 'app/src/main/assets/devtools-lite', file), 'utf8'), {filename: file});
}
for (const file of ['LICENSE', 'vendor/chobitsu-LICENSE', 'THIRD-PARTY-NOTICES.txt']) {
  assert.ok(fs.statSync(path.join(assets, file)).size > 0, file);
}
console.log(`PASS: ${Object.keys(manifest.files).length} verified offline assets, en-US, transport syntax and licenses`);
