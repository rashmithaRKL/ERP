// Cross-platform Maven runner: `node scripts/maven.mjs <maven args...>`.
// POSIX: the checksum-verified ./mvnw bootstrap (also used by CI). Windows: `mvn` from PATH, through a shell
// because mvn/npm are .cmd shims that Node can only launch that way.
import { spawn } from 'node:child_process';
import { realpathSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const root = fileURLToPath(new URL('../', import.meta.url));
const windows = process.platform === 'win32';

// cmd.exe receives one command line; quote plain arguments containing whitespace. Arguments that already carry their
// own quotes (for example -Dspring-boot.run.jvmArguments=-Ddexlanka.data="...") are passed through unchanged.
const shellArgument = argument => (/\s/.test(argument) && !argument.includes('"') ? `"${argument}"` : argument);

/** Spawns a command from the repository root with inherited stdio. */
export function spawnCommand(command, args = [], options = {}) {
  const settings = { cwd: root, stdio: 'inherit', ...options };
  if (!windows) return spawn(command, args, settings);
  return spawn([command, ...args].map(shellArgument).join(' '), { ...settings, shell: true });
}

/** Spawns Maven: ./mvnw on POSIX, mvn on Windows. */
export function spawnMaven(args, options = {}) {
  return spawnCommand(windows ? 'mvn' : resolve(root, 'mvnw'), args, options);
}

const invokedDirectly = (() => {
  if (!process.argv[1]) return false;
  const normalize = path => {
    const real = realpathSync(path);
    return windows ? real.toLowerCase() : real;
  };
  try {
    return normalize(process.argv[1]) === normalize(fileURLToPath(import.meta.url));
  } catch {
    return false;
  }
})();

if (invokedDirectly) {
  const child = spawnMaven(process.argv.slice(2));
  child.on('error', error => {
    console.error(error.message);
    process.exitCode = 1;
  });
  child.on('exit', (code, signal) => {
    process.exitCode = code ?? (signal ? 1 : 0);
  });
}
