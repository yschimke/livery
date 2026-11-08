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
    'dist/livery-web/livery-livery-web.wasm', 'vendor/remote-compose-player.mjs',
    'vendor/remote-compose-player.d.mts', 'vendor/PROVENANCE.md', 'README.md']) {
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
  for (const file of files) {
    assert(/^(dist\/|vendor\/|README.md$|LICENSE(?:\..*)?$|package.json$)/.test(file), `unexpected file: ${file}`);
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
