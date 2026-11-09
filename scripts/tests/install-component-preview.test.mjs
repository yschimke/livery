import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { createHash } from 'node:crypto';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
import { installPublication, validatePublication, publicationPath, LIMITS } from '../install-component-preview.mjs';

const exec = promisify(execFile);
const script = fileURLToPath(new URL('../install-component-preview.mjs', import.meta.url));
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const rc = Buffer.from('An opaque test RC payload; rendering is checked by Livery, not this installer.');
const hash = sha(rc);
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jR4kAAAAASUVORK5CYII=', 'base64');
const writeJson = (file, value) => fs.writeFile(file, `${JSON.stringify(value)}\n`);

async function fixture(t) {
  const temp = await fs.mkdtemp(path.join(os.tmpdir(), 'livery-installer-'));
  t.after(() => fs.rm(temp, { recursive: true, force: true }));
  const source = path.join(temp, 'bundle'), root = path.join(temp, 'publications');
  await fs.mkdir(path.join(source, 'catalog/blobs'), { recursive: true });
  const descriptor = {
    schemaVersion: 1, mode: 'preview', publicationId: 'test-component', revision: 'r1', canonicalUrl: './',
    catalog: { manifestUrl: './catalog/manifest.json' },
    template: { contract: 'test/component@1', designId: 'example', sha256: hash },
    data: { value: 1 }, fallback: { title: 'Example', description: 'A filesystem test' },
  };
  const manifest = {
    catalog: 'example', serial: 1, generatedAtEpochMs: 1, expiresAtEpochMs: 2,
    contracts: [{ id: 'test/component', major: 1 }],
    designs: [{ id: 'example', contract: 'test/component@1' }],
    templates: [{ sha256: hash, bytes: rc.length, designId: 'example', contract: 'test/component@1' }],
  };
  await writeJson(path.join(source, 'preview.json'), descriptor);
  await writeJson(path.join(source, 'catalog/manifest.json'), manifest);
  await fs.writeFile(path.join(source, 'thumbnail.png'), png);
  await fs.writeFile(path.join(source, `catalog/blobs/${hash}.rc`), rc);
  return { temp, source, root, descriptor, manifest, destination: path.join(root, 'test-component/r/r1') };
}

test('complete install preserves exact signed-manifest bytes, records allowlist and retries without rewriting', async t => {
  const f = await fixture(t);
  const signature = { alg: 'Ed25519', keyId: 'test', signature: 'test-envelope-not-a-trusted-signature' };
  await writeJson(path.join(f.source, 'catalog/manifest.sig.json'), signature);
  const manifestBytes = await fs.readFile(path.join(f.source, 'catalog/manifest.json'));
  assert.equal((await installPublication(f.source, f.root)).status, 'installed');
  const inventory = JSON.parse(await fs.readFile(path.join(f.destination, 'publication-files.json'), 'utf8'));
  assert.equal(Object.keys(inventory).length, 5);
  assert.equal(inventory['publication-files.json'], undefined);
  assert.equal(inventory['catalog/manifest.json'], sha(manifestBytes));
  assert.deepEqual(await fs.readFile(path.join(f.destination, 'catalog/manifest.json')), manifestBytes);
  const before = await fs.stat(path.join(f.destination, 'preview.json'));
  assert.equal((await installPublication(f.source, f.root)).status, 'unchanged');
  assert.equal((await fs.stat(path.join(f.destination, 'preview.json'))).mtimeMs, before.mtimeMs);
  assert.deepEqual(await fs.readdir(path.dirname(f.destination)), ['r1']);
});

test('concurrent independent CLI installers expose one complete immutable revision', async t => {
  const f = await fixture(t);
  const results = await Promise.all(Array.from({ length: 4 }, () => exec(process.execPath, [script, f.source, f.root])));
  const statuses = results.map(result => JSON.parse(result.stdout).status);
  assert.equal(statuses.filter(status => status === 'installed').length, 1);
  assert.equal(statuses.filter(status => status === 'unchanged').length, 3);
  assert.equal(Object.keys(JSON.parse(await fs.readFile(path.join(f.destination, 'publication-files.json')))).length, 4);
  assert.deepEqual(await fs.readdir(path.dirname(f.destination)), ['r1']);
});

test('same identity with different public data is rejected without changing installed bytes', async t => {
  const f = await fixture(t);
  await installPublication(f.source, f.root);
  const original = await fs.readFile(path.join(f.destination, 'preview.json'));
  f.descriptor.data.value = 2;
  await writeJson(path.join(f.source, 'preview.json'), f.descriptor);
  await assert.rejects(installPublication(f.source, f.root), /different bytes/);
  assert.deepEqual(await fs.readFile(path.join(f.destination, 'preview.json')), original);
});

test('concurrent conflicting publishers cannot replace the winning revision', async t => {
  const f = await fixture(t);
  const other = path.join(f.temp, 'other');
  await fs.cp(f.source, other, { recursive: true });
  await writeJson(path.join(other, 'preview.json'), { ...f.descriptor, data: { value: 2 } });
  const results = await Promise.allSettled([f.source, other].map(source => exec(process.execPath, [script, source, f.root])));
  assert.equal(results.filter(result => result.status === 'fulfilled').length, 1);
  assert.match(results.find(result => result.status === 'rejected').reason.stderr, /different bytes/);
  const installed = JSON.parse(await fs.readFile(path.join(f.destination, 'preview.json'), 'utf8'));
  assert.equal((await installPublication(installed.data.value === 1 ? f.source : other, f.root)).status, 'unchanged');
  assert.deepEqual(await fs.readdir(path.dirname(f.destination)), ['r1']);
});

test('retry detects installed byte corruption even with unchanged inventory', async t => {
  const f = await fixture(t);
  await installPublication(f.source, f.root);
  await fs.writeFile(path.join(f.destination, `catalog/blobs/${hash}.rc`), 'tampered');
  await assert.rejects(installPublication(f.source, f.root), /corrupt/);
});

test('withdrawn identity is reserved even after its original publication files were removed', async t => {
  const f = await fixture(t);
  await fs.mkdir(f.destination, { recursive: true });
  await fs.writeFile(path.join(f.destination, 'withdrawn'), 'operator withdrawal');
  await assert.rejects(installPublication(f.source, f.root), /withdrawn/);
  assert.deepEqual(await fs.readdir(f.destination), ['withdrawn']);
});

for (const [name, mutate] of [
  ['missing pinned blob', f => fs.rm(path.join(f.source, `catalog/blobs/${hash}.rc`))],
  ['corrupt pinned blob', f => fs.writeFile(path.join(f.source, `catalog/blobs/${hash}.rc`), 'corrupt')],
  ['wrong descriptor pin', async f => { f.descriptor.template.sha256 = 'a'.repeat(64); await writeJson(path.join(f.source, 'preview.json'), f.descriptor); }],
  ['wrong byte count', async f => { f.manifest.templates[0].bytes++; await writeJson(path.join(f.source, 'catalog/manifest.json'), f.manifest); }],
  ['missing required asset', async f => { f.manifest.templates[0].assets = ['b'.repeat(64)]; await writeJson(path.join(f.source, 'catalog/manifest.json'), f.manifest); }],
  ['missing preview', async f => { f.manifest.designs[0].previews = [{ sha256: 'c'.repeat(64), mime: 'image/png' }]; await writeJson(path.join(f.source, 'catalog/manifest.json'), f.manifest); }],
  ['absolute catalog base', async f => { f.manifest.blobBase = 'https://example.com/blobs/'; await writeJson(path.join(f.source, 'catalog/manifest.json'), f.manifest); }],
  ['escaping catalog base', async f => { f.manifest.blobBase = '../other/'; await writeJson(path.join(f.source, 'catalog/manifest.json'), f.manifest); }],
  ['root-relative canonical URL', async f => { f.descriptor.canonicalUrl = '/somewhere/'; await writeJson(path.join(f.source, 'preview.json'), f.descriptor); }],
  ['network-path manifest URL', async f => { f.descriptor.catalog.manifestUrl = '//elsewhere/manifest.json'; await writeJson(path.join(f.source, 'preview.json'), f.descriptor); }],
  ['invalid identity', async f => { f.descriptor.revision = '../other'; await writeJson(path.join(f.source, 'preview.json'), f.descriptor); }],
  ['non-string identity', async f => { f.descriptor.revision = 123; await writeJson(path.join(f.source, 'preview.json'), f.descriptor); }],
  ['non-PNG thumbnail', f => fs.writeFile(path.join(f.source, 'thumbnail.png'), '<svg/>')],
  ['HTML active content', f => fs.writeFile(path.join(f.source, 'catalog/index.html'), '<script/>')],
  ['source private key', f => fs.writeFile(path.join(f.source, 'publisher.key'), 'private key')],
  ['unreferenced JSON credentials', f => writeJson(path.join(f.source, 'catalog/credentials.json'), { token: 'private' })],
  ['executable RC file', f => fs.chmod(path.join(f.source, `catalog/blobs/${hash}.rc`), 0o755)],
  ['source inventory supplied by caller', f => writeJson(path.join(f.source, 'publication-files.json'), {})],
  ['remote font', async f => { f.manifest.templates[0].fonts = ['google:Roboto']; await writeJson(path.join(f.source, 'catalog/manifest.json'), f.manifest); }],
]) {
  test(`rejects ${name} before creating any publication destination`, async t => {
    const f = await fixture(t);
    await mutate(f);
    await assert.rejects(installPublication(f.source, f.root));
    await assert.rejects(fs.stat(f.root), { code: 'ENOENT' });
  });
}

test('catalog assets and design/theme previews must be local, hash verified and complete', async t => {
  const f = await fixture(t);
  const imageHash = sha(png), font = Buffer.from('test font bytes'), fontHash = sha(font);
  await fs.mkdir(path.join(f.source, 'catalog/assets'));
  await fs.mkdir(path.join(f.source, 'catalog/previews'));
  await fs.writeFile(path.join(f.source, `catalog/assets/${fontHash}.ttf`), font);
  await fs.writeFile(path.join(f.source, `catalog/previews/${imageHash}.png`), png);
  f.manifest.assets = [{ sha256: fontHash, bytes: font.length, mime: 'font/ttf', kind: 'font' }];
  f.manifest.templates[0].assets = [fontHash];
  f.manifest.designs[0].previews = [{ sha256: imageHash, mime: 'image/png' }];
  f.manifest.themes = [{ id: 'light', previews: [{ sha256: imageHash, mime: 'image/png' }] }];
  await writeJson(path.join(f.source, 'catalog/manifest.json'), f.manifest);
  assert.equal((await installPublication(f.source, f.root)).status, 'installed');
});

test('rejects symlink files, source parents, and destination parents', async t => {
  const f = await fixture(t);
  const blob = path.join(f.source, `catalog/blobs/${hash}.rc`);
  await fs.rename(blob, path.join(f.temp, 'outside.rc'));
  await fs.symlink(path.join(f.temp, 'outside.rc'), blob);
  await assert.rejects(installPublication(f.source, f.root), /Symlink/);
  await fs.rm(blob);
  await fs.writeFile(blob, rc);
  await fs.symlink(f.source, path.join(f.temp, 'alias'));
  await assert.rejects(installPublication(path.join(f.temp, 'alias'), f.root), /symlinks/);
  await fs.mkdir(f.root);
  await fs.mkdir(path.join(f.temp, 'outside'));
  await fs.symlink(path.join(f.temp, 'outside'), path.join(f.root, 'test-component'));
  await assert.rejects(installPublication(f.source, f.root), /symlinks/);
  assert.deepEqual(await fs.readdir(path.join(f.temp, 'outside')), []);
});

test('bounded files, JSON and inventory path grammar reject oversized or ambiguous input', async t => {
  const f = await fixture(t);
  for (const invalid of ['../x', '/root', 'a//b', 'a\\b', 'a/%2e%2e/b', '.secret', 'a/.hidden', 'a'.repeat(101), 'a/'.repeat(121)]) assert.throws(() => publicationPath(invalid));
  const thumbnail = await fs.open(path.join(f.source, 'thumbnail.png'), 'w');
  await thumbnail.truncate(LIMITS.fileBytes + 1);
  await thumbnail.close();
  await assert.rejects(validatePublication(f.source), /oversized/);
  await fs.writeFile(path.join(f.source, 'thumbnail.png'), png);
  await fs.writeFile(path.join(f.source, 'preview.json'), ' '.repeat(LIMITS.jsonBytes + 1));
  await assert.rejects(validatePublication(f.source), /oversized/);
});

test('file count and aggregate byte limits apply before any revision is installed', async t => {
  const f = await fixture(t);
  for (let i = 0; i < LIMITS.files; i++) await fs.writeFile(path.join(f.source, `catalog/file-${i}.bin`), 'x');
  await assert.rejects(installPublication(f.source, f.root), /Too many/);
  await fs.rm(path.join(f.source, 'catalog'), { recursive: true });
  await fs.mkdir(path.join(f.source, 'catalog'));
  for (let i = 0; i < 3; i++) {
    const handle = await fs.open(path.join(f.source, `catalog/large-${i}.bin`), 'w');
    await handle.truncate(12 * 1024 * 1024);
    await handle.close();
  }
  await assert.rejects(installPublication(f.source, f.root), /total byte budget/);
  await assert.rejects(fs.stat(f.root), { code: 'ENOENT' });
});

test('fallback metadata accepts exact server bounds and rejects the next character', async t => {
  const f = await fixture(t);
  f.descriptor.fallback = { title: 't'.repeat(200), description: 'd'.repeat(2000) };
  await writeJson(path.join(f.source, 'preview.json'), f.descriptor);
  await validatePublication(f.source);
  f.descriptor.fallback.title += 'x';
  await writeJson(path.join(f.source, 'preview.json'), f.descriptor);
  await assert.rejects(installPublication(f.source, f.root), /server limits/);
  f.descriptor.fallback.title = 't';
  f.descriptor.fallback.description += 'x';
  await writeJson(path.join(f.source, 'preview.json'), f.descriptor);
  await assert.rejects(installPublication(f.source, f.root), /server limits/);
  await assert.rejects(fs.stat(f.root), { code: 'ENOENT' });
});
