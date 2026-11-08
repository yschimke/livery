import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
for (const renderer of ['lit', 'react', 'angular']) {
  console.log(`\nRenderer: ${renderer}`);
  execFileSync(process.execPath, ['test/e2e.mjs'], {
    stdio: 'inherit',
    env: { ...process.env, LIVERY_RENDERER: renderer,
      ...(process.env.LIVERY_RENDER_OUT ? { LIVERY_RENDER_OUT: join(process.env.LIVERY_RENDER_OUT, renderer) } : {}) },
  });
}
