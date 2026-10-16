import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const [lockFile, output, cacheRoot] = process.argv.slice(2);
assert.ok(lockFile && output && cacheRoot, 'Usage: prepare-assets.mjs LOCK OUTPUT CACHE');
const {repository, commit} = JSON.parse(fs.readFileSync(lockFile, 'utf8'));
assert.match(commit || '', /^[a-f0-9]{40}$/, 'DevTools dist is not pinned. Set commit in ' + lockFile +
  ' to a real, full dist commit SHA before building. Source/chii commits are not dist commits.');
assert.ok(typeof repository === 'string' && /^(https:\/\/|file:\/\/)/.test(repository),
  'DevTools repository must be an HTTPS URL (or file URL for local verification)');
const cache = path.resolve(cacheRoot, createHash('sha256').update(repository).digest('hex').slice(0, 16));
fs.mkdirSync(path.dirname(cache), {recursive: true});
const git = (...args) => execFileSync('git', ['-C', cache, ...args],
  {encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe']}).trim();
const containsPin = () => {
  try {
    git('merge-base', '--is-ancestor', commit, 'refs/remotes/origin/dist');
    return true;
  } catch { return false; }
};
if (!fs.existsSync(cache)) {
  execFileSync('git', ['clone', '--config', 'core.autocrlf=false', '--single-branch', '--branch', 'dist', '--depth=1', '--no-tags',
    '--no-checkout', '--', repository, cache], {stdio: 'inherit'});
}
assert.ok(!fs.lstatSync(cache).isSymbolicLink(), 'Refusing a redirected dist cache');
assert.equal(git('remote', 'get-url', 'origin'), repository);
assert.equal(git('config', '--get-all', 'remote.origin.fetch'),
  '+refs/heads/dist:refs/remotes/origin/dist', 'The cache must fetch only dist');
if (!containsPin()) {
  git('fetch', '--no-tags', 'origin', 'refs/heads/dist:refs/remotes/origin/dist');
  for (let depth = 32; !containsPin() && git('rev-parse', '--is-shallow-repository') === 'true'; depth *= 2) {
    git('fetch', '--no-tags', `--deepen=${depth}`, 'origin', 'refs/heads/dist:refs/remotes/origin/dist');
  }
}
assert.ok(containsPin(), `Pinned commit ${commit} is not in dist history; refusing another branch`);
git('checkout', '--detach', '--force', commit);
assert.equal(git('rev-parse', 'HEAD'), commit);
execFileSync(process.execPath, [path.join(root, 'app/tools/devtools-lite/import-assets.mjs'), cache, output],
  {stdio: 'inherit'});
