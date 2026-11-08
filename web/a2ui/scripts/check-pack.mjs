// Inspect the actual tarball npm would publish, including its runtime imports.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const dir = mkdtempSync(join(tmpdir(), 'livery-pack-'));
try {
  const [pack] = JSON.parse(execFileSync('npm', ['pack', '--json', '--pack-destination', dir], { encoding: 'utf8' }));
  const files = new Set(pack.files.map((f) => f.path));
  for (const file of ['dist/index.js', 'dist/index.d.ts', 'dist/livery-web/livery-livery-web.mjs',
    'dist/livery-web/livery-livery-web.wasm', 'node_modules/@livery/web/dist/player.js', 'node_modules/@livery/web/dist/player.d.ts',
    'node_modules/@livery/web/vendor/remote-compose-player.mjs',
    'node_modules/@livery/web/vendor/remote-compose-player.d.mts', 'node_modules/@livery/web/vendor/PROVENANCE.md',
    'node_modules/@livery/web/package.json',
    'node_modules/@livery/web/dist/livery-web/livery-livery-web-core.mjs',
    'node_modules/@livery/web/dist/livery-web/livery-livery-web-core.wasm', 'README.md']) {
    assert(files.has(file), `missing runtime file: ${file}`);
  }
  if ([...files].some((f) => f.startsWith('dist/livery-web-compose/'))) {
    for (const file of ['livery-livery-web-compose.mjs', 'livery-livery-web-compose.wasm',
      'skiko.mjs', 'skiko.wasm', 'fonts/RobotoFlex.ttf', 'fonts/NotoSansSymbols2-Regular.ttf']) {
      assert(files.has(`dist/livery-web-compose/${file}`), `missing CMP runtime file: ${file}`);
    }
  }
  const manifest = JSON.parse(readFileSync('package.json', 'utf8'));
  function checkExports(value) {
    if (typeof value === 'string') {
      if (value.startsWith('./') && !value.includes('*')) assert(files.has(value.slice(2)), `missing export: ${value}`);
    } else if (value && typeof value === 'object') Object.values(value).forEach(checkExports);
  }
  checkExports(manifest.exports);
  assert(manifest.bundleDependencies.includes('@livery/web'), 'local player must be bundled');
  const bundledManifest = JSON.parse(execFileSync('tar', ['-xOf', join(dir, pack.filename), 'package/node_modules/@livery/web/package.json'], { encoding: 'utf8' }));
  for (const target of Object.values(bundledManifest.exports['./player'])) {
    assert(files.has(`node_modules/@livery/web/${target.slice(2)}`), `missing bundled player export: ${target}`);
  }
  // npm also bundles the player's declared runtime dependency. Validate its published
  // entry points; upstream source maps and source files are part of that package.
  const jodaManifest = JSON.parse(execFileSync('tar', ['-xOf', join(dir, pack.filename), 'package/node_modules/@js-joda/core/package.json'], { encoding: 'utf8' }));
  for (const field of ['main', 'module', 'types']) {
    if (jodaManifest[field]) assert(files.has(`node_modules/@js-joda/core/${jodaManifest[field].replace(/^\.\//, '')}`), `missing js-joda ${field}`);
  }
  for (const file of files) {
    if (file.startsWith('node_modules/@js-joda/core/')) continue;
    assert(/^(dist\/|vendor\/|node_modules\/@livery\/web\/|README.md$|LICENSE(?:\..*)?$|package.json$)/.test(file), `unexpected file: ${file}`);
    assert(!/\.(map|tgz)$/.test(file), `development artifact: ${file}`);
    if (!file.endsWith('.js') && !file.endsWith('.mjs')) continue;
    const source = execFileSync('tar', ['-xOf', join(dir, pack.filename), `package/${file}`], { encoding: 'utf8' });
    for (const [, relative] of source.matchAll(/(?:from\s*|import\s*)['"](\.\.?\/[^'"]+)['"]/g)) {
      const target = new URL(relative, `https://package/${file}`).pathname.slice(1);
      assert(files.has(target), `${file} imports missing ${target}`);
    }
  }
  assert.equal(pack.version, JSON.parse(readFileSync('package.json', 'utf8')).version);
  console.log(`${pack.name}@${pack.version}: ${files.size} files, ${pack.size} bytes packed; runtime files present`);
} finally {
  rmSync(dir, { recursive: true, force: true });
}
