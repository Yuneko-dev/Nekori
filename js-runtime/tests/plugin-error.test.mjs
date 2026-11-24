import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  PluginError,
  assertNoChallenge,
  checkResponse,
  createPluginError,
  httpError,
  isChallengeHtml,
  isPluginError,
} from '../src/plugins/helpers/pluginError.ts';

test('createPluginError attaches structured metadata and matches isPluginError', () => {
  const err = createPluginError(PluginError.RateLimited, {
    status: 429,
    url: 'https://example.com/ch/1',
  });
  assert.equal(err.name, 'PluginError');
  assert.equal(err.code, PluginError.RateLimited);
  assert.equal(err.message, PluginError.RateLimited);
  assert.equal(err.status, 429);
  assert.equal(err.url, 'https://example.com/ch/1');
  assert.equal(isPluginError(err), true);
  assert.equal(isPluginError(new Error('plain')), false);
});

test('httpError and checkResponse map HTTP statuses to PluginError codes', () => {
  assert.equal(httpError(401).code, PluginError.AuthRequired);
  assert.equal(httpError(403).code, PluginError.Cloudflare);
  assert.equal(httpError(404).code, PluginError.NotFound);
  assert.equal(httpError(429).code, PluginError.RateLimited);
  assert.equal(httpError(503).code, PluginError.Cloudflare);
  const netErr = httpError(502, undefined, 'https://example.com');
  assert.equal(netErr.code, PluginError.NetworkError);
  assert.match(netErr.message, /502/);

  const search404 = { ok: false, status: 404, url: 'https://example.com/search' };
  assert.equal(checkResponse(search404, { search: true }), search404);

  assert.throws(
    () =>
      checkResponse(
        { ok: false, status: 404, url: 'https://example.com/novel/1' },
        { notFoundCode: PluginError.NovelNotFound },
      ),
    err => isPluginError(err) && err.code === PluginError.NovelNotFound && err.status === 404,
  );
});

test('isChallengeHtml and assertNoChallenge catch Cloudflare and cross-host redirects', () => {
  assert.equal(
    isChallengeHtml('<html><head><title>Just a moment...</title></head></html>'),
    true,
  );
  assert.equal(
    isChallengeHtml(
      '<html><head><title>Login</title></head></html>',
      'https://www.example.com/novel/1',
      'https://auth.otherhost.org/verify',
    ),
    true,
  );
  assert.equal(
    isChallengeHtml(
      '<html><head><title>Chapter 1</title></head></html>',
      'https://example.com/ch/1',
      'https://www.example.com/ch/1',
    ),
    false,
  );
  assert.throws(
    () =>
      assertNoChallenge(
        '<html><head><title>Attention Required! | Cloudflare</title></head></html>',
        'https://example.com/ch/1',
      ),
    err => isPluginError(err) && err.code === PluginError.Captcha && err.status === 403,
  );
});
