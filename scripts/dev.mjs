import { resolve } from 'node:path';
import { root, spawnCommand, spawnMaven } from './maven.mjs';

const apiOnly = process.argv.includes('--api-only');
const data = resolve(root, '.local/postgres');
// Windows: mvn/npm are .cmd shims, which Node can only launch through a shell (handled by maven.mjs).
const processes = [
  spawnMaven([
    '-f',
    'services/erp-api/pom.xml',
    'spring-boot:run',
    '-Dspring-boot.run.profiles=demo',
    `-Dspring-boot.run.jvmArguments=-Ddexlanka.data="${data}"`,
  ]),
];
if (!apiOnly) processes.push(spawnCommand('npm', ['run', 'dev']));
let stopping = false;
function stop(code = 0) {
  if (stopping) return;
  stopping = true;
  for (const p of processes) if (!p.killed) p.kill('SIGTERM');
  process.exitCode = code;
}
for (const p of processes) {
  p.on('error', e => {
    console.error(e.message);
    stop(1);
  });
  p.on('exit', code => {
    if (!stopping) stop(code || 0);
  });
}
process.on('SIGINT', () => stop());
process.on('SIGTERM', () => stop());
