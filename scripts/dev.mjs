import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
const root = fileURLToPath(new URL('../', import.meta.url));
const apiOnly = process.argv.includes('--api-only');
const command = process.platform === 'win32' ? 'mvn' : resolve(root, 'mvnw');
const data = resolve(root, '.local/postgres');
// Windows: mvn/npm are .cmd shims, which Node can only launch through a shell.
const options = { cwd: root, stdio: 'inherit', shell: process.platform === 'win32' };
const processes = [spawn(command, ['-f', 'services/erp-api/pom.xml', 'spring-boot:run', '-Dspring-boot.run.profiles=demo', `-Dspring-boot.run.jvmArguments=-Ddexlanka.data="${data}"`], options)];
if (!apiOnly) processes.push(spawn('npm', ['run', 'dev'], options));
let stopping = false;
function stop(code = 0) { if (stopping) return; stopping = true; for (const p of processes) if (!p.killed) p.kill('SIGTERM'); process.exitCode = code; }
for (const p of processes) { p.on('error', e => { console.error(e.message); stop(1); }); p.on('exit', code => { if (!stopping) stop(code || 0); }); }
process.on('SIGINT', () => stop()); process.on('SIGTERM', () => stop());
