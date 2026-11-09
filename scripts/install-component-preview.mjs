#!/usr/bin/env node
// Experimental, operator-local publication packaging. Livery's runtime gates still own trust,
// contract binding, capability selection and action admission.
import { constants } from 'node:fs';
import * as fs from 'node:fs/promises';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { pathToFileURL } from 'node:url';

export const LIMITS = Object.freeze({ files: 128, fileBytes: 16 * 1024 * 1024, totalBytes: 32 * 1024 * 1024, jsonBytes: 1024 * 1024, inventoryBytes: 64 * 1024 });
const INVENTORY = 'publication-files.json';
const HASH = /^[a-f0-9]{64}$/;
const ID = /^[a-z0-9][a-z0-9-]{0,63}$/;
const SEGMENT = /^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/;
const MIME_EXT = new Map([['image/png', 'png'], ['image/jpeg', 'jpeg'], ['image/webp', 'webp'], ['font/ttf', 'ttf'], ['font/otf', 'otf'], ['font/woff2', 'woff2'], ['application/octet-stream', 'bin']]);
const PNG_MAGIC = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const fail = message => { throw new Error(message); };

export function publicationPath(name) {
  if (typeof name !== 'string' || name.length > 240 || !name.split('/').every(part => SEGMENT.test(part))) fail(`Invalid publication path: ${name}`);
  return name;
}

function allowedFile(name) {
  publicationPath(name);
  return name === 'preview.json' || name === 'thumbnail.png' ||
    (name.startsWith('catalog/') && /\.(json|rc|bin|png|jpg|jpeg|webp|ttf|otf|woff2)$/.test(name));
}

async function regularDirectory(directory, create = false) {
  const absolute = path.resolve(directory);
  let current = path.parse(absolute).root;
  for (const segment of absolute.slice(current.length).split(path.sep).filter(Boolean)) {
    current = path.join(current, segment);
    if (create) await fs.mkdir(current).catch(error => { if (error.code !== 'EEXIST') throw error; });
    const stat = await fs.lstat(current);
    if (!stat.isDirectory() || stat.isSymbolicLink()) fail(`Expected directory without symlinks: ${current}`);
  }
  return absolute;
}

async function readBounded(file, maximum) {
  await regularDirectory(path.dirname(file));
  const handle = await fs.open(file, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    const stat = await handle.stat();
    if (!stat.isFile() || stat.size > maximum || (stat.mode & 0o111)) fail(`Invalid, executable or oversized file: ${file}`);
    // Bound the read itself as well as stat: a growing source must not defeat the memory budget.
    const buffer = Buffer.alloc(stat.size + 1);
    let length = 0;
    while (length < buffer.length) {
      const { bytesRead } = await handle.read(buffer, length, buffer.length - length, null);
      if (!bytesRead) break;
      length += bytesRead;
    }
    if (length !== stat.size) fail(`File changed while reading: ${file}`);
    return buffer.subarray(0, length);
  } finally { await handle.close(); }
}

function json(bytes, name, maximum = LIMITS.jsonBytes) {
  if (!bytes || bytes.length > maximum) fail(`Missing or oversized JSON: ${name}`);
  try { return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)); }
  catch { fail(`Invalid JSON: ${name}`); }
}

function inventoryBytes(files) {
  const inventory = Object.fromEntries([...files].sort(([a], [b]) => a.localeCompare(b)).map(([name, bytes]) => [name, digest(bytes)]));
  const bytes = Buffer.from(`${JSON.stringify(inventory, null, 2)}\n`);
  if (bytes.length > LIMITS.inventoryBytes) fail('Inventory too large');
  return { inventory, bytes };
}

async function readBundle(directory, installed = false) {
  const root = await regularDirectory(directory);
  const files = new Map();
  let total = 0;
  let directories = 0;
  async function visit(relative = '') {
    const entries = await fs.readdir(path.join(root, relative), { withFileTypes: true });
    for (const entry of entries) {
      const name = relative ? `${relative}/${entry.name}` : entry.name;
      if (installed && name === 'withdrawn') fail('Publication is withdrawn; its identity cannot be reused');
      if (installed && name === INVENTORY) continue;
      publicationPath(name);
      if (entry.isSymbolicLink()) fail(`Symlink not allowed: ${name}`);
      if (entry.isDirectory()) {
        if (++directories > LIMITS.files || !(name === 'catalog' || name.startsWith('catalog/'))) fail(`Unexpected directory: ${name}`);
        await visit(name);
      } else {
        if (!entry.isFile() || !allowedFile(name)) fail(`Unexpected publication file: ${name}`);
        if (files.size >= LIMITS.files) fail('Too many publication files');
        const bytes = await readBounded(path.join(root, name), name.endsWith('.json') ? LIMITS.jsonBytes : LIMITS.fileBytes);
        total += bytes.length;
        if (total > LIMITS.totalBytes) fail('Publication exceeds total byte budget');
        files.set(name, bytes);
      }
    }
  }
  await visit();
  return files;
}

function catalogBase(value, fallback) {
  const base = value ?? fallback;
  if (typeof base !== 'string' || !base.endsWith('/')) fail('Catalog bases must be relative directories');
  const relative = base.startsWith('./') ? base.slice(2) : base;
  publicationPath(`catalog/${relative.slice(0, -1)}`);
  return `catalog/${relative}`;
}

function validateFiles(files) {
  const descriptor = json(files.get('preview.json'), 'preview.json');
  if (!object(descriptor) || descriptor.schemaVersion !== 1 || descriptor.mode !== 'preview' ||
    ![descriptor.publicationId, descriptor.revision].every(value => typeof value === 'string' && ID.test(value))) fail('Invalid preview version, mode or identity');
  if (descriptor.canonicalUrl !== './' || descriptor.catalog?.manifestUrl !== './catalog/manifest.json') fail('Preview URLs must use fixed same-publication relative paths');
  if (!object(descriptor.template) || !HASH.test(descriptor.template.sha256 ?? '') || !object(descriptor.data) || !object(descriptor.fallback)) fail('Incomplete preview descriptor');
  for (const value of [descriptor.template.contract, descriptor.template.designId, descriptor.fallback.title, descriptor.fallback.description]) {
    if (typeof value !== 'string' || !value.length) fail('Invalid descriptor text or template reference');
  }
  if (descriptor.fallback.title.length > 200 || descriptor.fallback.description.length > 2000) fail('Fallback metadata exceeds server limits');
  if (!files.get('thumbnail.png')?.subarray(0, 8).equals(PNG_MAGIC)) fail('thumbnail.png must be a PNG');
  const manifest = json(files.get('catalog/manifest.json'), 'catalog/manifest.json');
  if (!object(manifest) || (manifest.schema ?? 1) !== 1 || !Array.isArray(manifest.templates) || !Array.isArray(manifest.contracts) || !Array.isArray(manifest.designs)) fail('Incomplete catalog manifest');
  if (manifest.imageProxy != null) fail('Remote image proxies are outside an offline publication');
  const blobBase = catalogBase(manifest.blobBase, 'blobs/');
  const previewBase = catalogBase(manifest.previewBase, 'previews/');
  const assetBase = catalogBase(manifest.assetBase, 'assets/');
  const expected = new Set(['preview.json', 'thumbnail.png', 'catalog/manifest.json']);
  function checkFile(base, entry, extension, checkSize) {
    if (!object(entry) || !HASH.test(entry.sha256 ?? '') || !extension) fail('Invalid catalog file reference');
    const name = publicationPath(`${base}${entry.sha256}.${extension}`);
    const bytes = files.get(name);
    if (!bytes || digest(bytes) !== entry.sha256) fail(`Missing or corrupt catalog file: ${name}`);
    if (checkSize && (!Number.isSafeInteger(entry.bytes) || entry.bytes !== bytes.length)) fail(`Catalog byte count mismatch: ${name}`);
    expected.add(name);
  }
  const assets = manifest.assets ?? [];
  if (!Array.isArray(assets)) fail('Invalid catalog assets');
  for (const asset of assets) checkFile(assetBase, asset, MIME_EXT.get(asset?.mime), true);
  for (const template of manifest.templates) {
    checkFile(blobBase, template, 'rc', true);
    if (!Array.isArray(template.assets ?? []) || (template.assets ?? []).some(hash => !assets.some(asset => asset.sha256 === hash))) fail('Template references a missing catalog asset');
    if (!Array.isArray(template.fonts ?? []) || (template.fonts ?? []).some(font => typeof font !== 'string' || font.startsWith('google:'))) fail('Remote font references are outside an offline publication');
  }
  const themes = manifest.themes ?? [];
  if (!Array.isArray(themes)) fail('Invalid catalog themes');
  for (const owner of [...manifest.designs, ...themes]) {
    if (!Array.isArray(owner.previews ?? [])) fail('Invalid catalog previews');
    for (const preview of owner.previews ?? []) checkFile(previewBase, preview, MIME_EXT.get(preview.mime ?? 'image/webp'), false);
  }
  const pin = descriptor.template;
  if (!manifest.templates.some(template => template.sha256 === pin.sha256 && template.designId === pin.designId && template.contract === pin.contract)) fail('Descriptor pin is absent from the catalog');
  if (!manifest.designs.some(design => design.id === pin.designId && design.contract === pin.contract && design.revoked !== true) || !manifest.contracts.some(contract => `${contract.id}@${contract.major}` === pin.contract)) fail('Pinned design or contract is missing/revoked');
  if (files.has('catalog/manifest.sig.json')) {
    const signature = json(files.get('catalog/manifest.sig.json'), 'catalog/manifest.sig.json');
    if (!object(signature) || (signature.alg ?? 'Ed25519') !== 'Ed25519' || typeof signature.keyId !== 'string' || typeof signature.signature !== 'string') fail('Invalid detached signature envelope');
    expected.add('catalog/manifest.sig.json');
  }
  for (const name of files.keys()) if (!expected.has(name)) fail(`Unreferenced publication file: ${name}`);
  return descriptor;
}

/** No copying of source tree, credentials or executable viewer assets: only a complete public bundle. */
export async function validatePublication(directory) {
  const files = await readBundle(directory);
  const descriptor = validateFiles(files);
  return { files, descriptor, ...inventoryBytes(files) };
}

async function existingMatches(destination, expected) {
  const stat = await fs.lstat(destination).catch(error => { if (error.code === 'ENOENT') return null; throw error; });
  if (!stat) return false;
  if (!stat.isDirectory() || stat.isSymbolicLink()) fail('Publication destination is not a regular directory');
  const files = await readBundle(destination, true);
  validateFiles(files);
  const stored = json(await readBounded(path.join(destination, INVENTORY), LIMITS.inventoryBytes), INVENTORY, LIMITS.inventoryBytes);
  const actual = inventoryBytes(files).inventory;
  if (!object(stored) || Object.keys(stored).length !== Object.keys(actual).length || Object.entries(actual).some(([name, hash]) => stored[name] !== hash)) fail('Installed publication inventory is corrupt');
  if (Object.keys(expected).length !== Object.keys(actual).length || Object.entries(expected).some(([name, hash]) => actual[name] !== hash)) fail('Publication identity already exists with different bytes');
  return true;
}

/** Atomic visibility on a local filesystem. Keep source and publication root operator-owned. */
export async function installPublication(source, publicationRoot) {
  const bundle = await validatePublication(source);
  const { publicationId, revision } = bundle.descriptor;
  const root = await regularDirectory(publicationRoot, true);
  const parent = await regularDirectory(path.join(root, publicationId, 'r'), true);
  const destination = path.join(parent, revision);
  if (await existingMatches(destination, bundle.inventory)) return { status: 'unchanged', destination };
  const staging = await fs.mkdtemp(path.join(parent, '.install-'));
  try {
    for (const [name, bytes] of [...bundle.files, [INVENTORY, bundle.bytes]]) {
      const target = path.join(staging, name);
      await fs.mkdir(path.dirname(target), { recursive: true });
      const handle = await fs.open(target, 'wx', 0o644);
      try { await handle.writeFile(bytes); await handle.sync(); } finally { await handle.close(); }
    }
    await fs.chmod(staging, 0o755);
    try { await fs.rename(staging, destination); }
    catch (error) {
      if (!['EEXIST', 'ENOTEMPTY'].includes(error.code) || !await existingMatches(destination, bundle.inventory)) throw error;
      return { status: 'unchanged', destination };
    }
    return { status: 'installed', destination };
  } finally { await fs.rm(staging, { recursive: true, force: true }); }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const [source, root, ...rest] = process.argv.slice(2);
  if (!source || !root || rest.length) {
    console.error('Usage: node scripts/install-component-preview.mjs <complete-bundle> <publication-root>');
    process.exitCode = 2;
  } else {
    try { console.log(JSON.stringify(await installPublication(source, root))); }
    catch (error) { console.error(error.message); process.exitCode = 1; }
  }
}
