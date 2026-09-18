#!/usr/bin/env node
// Build the pinned private source at distribution time, never on an end user's machine.
import {createHash} from 'node:crypto';
import {cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync} from 'node:fs';
import {dirname, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const options = Object.fromEntries(process.argv.slice(2).reduce((pairs, value, index, args) =>
  index % 2 === 0 ? [...pairs, [value, args[index + 1]]] : pairs, []));
const pin = JSON.parse(readFileSync(join(root, 'monitoring/auditlog-source.json'), 'utf8'));
if (!/^[a-f0-9]{40}$/.test(pin.commit)) throw new Error('Auditlog must be pinned to a full source commit');
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const recipe = sha(readFileSync(fileURLToPath(import.meta.url))).slice(0, 16);
const cache = join(root, '.private', 'monitoring', `${pin.commit}-${recipe}`);
const output = resolve(options['--output'] ?? process.env.AUDITLOG_OUTPUT ?? join(root, 'target/generated-resources/dev-monitoring'));
const tools = resolve(options['--tools'] ?? process.env.AUDITLOG_TOOLS ?? join(root, 'target/frontend-tools'));
const javaHome = options['--java-home'] ?? process.env.JAVA_HOME;
if (!javaHome) throw new Error('A Java 25 JDK is required to package monitoring');
const windows = process.platform === 'win32';
const jar = join(javaHome, 'bin', windows ? 'jar.exe' : 'jar');
const bundle = join(cache, 'auditlog.zip');
const manifestFile = join(cache, 'manifest.json');

function run(command, args, cwd) {
  const result = spawnSync(command, args, {cwd, stdio: 'inherit',
    shell: windows && command.endsWith('.cmd'), timeout: 15 * 60 * 1000,
    env: {...process.env, JAVA_HOME: javaHome}});
  if (result.error || result.status !== 0) throw new Error(`Monitoring build command failed: ${command} (exit ${result.status})`);
}

function filesUnder(directory, prefix = '') {
  return readdirSync(directory, {withFileTypes: true}).sort((a, b) => a.name.localeCompare(b.name)).flatMap(entry => {
    const name = prefix + entry.name;
    if (entry.isSymbolicLink()) throw new Error(`Symlink is not a monitoring artifact: ${name}`);
    return entry.isDirectory() ? filesUnder(join(directory, entry.name), name + '/') : [name];
  });
}

const reusable = existsSync(bundle) && existsSync(manifestFile)
  && JSON.parse(readFileSync(manifestFile, 'utf8')).sha256 === sha(readFileSync(bundle));
if (!reusable) {
  mkdirSync(cache, {recursive: true});
  const origin = join(cache, 'source.git');
  if (!existsSync(origin)) run('git', ['init', '--bare', origin], root);
  const source = options['--source'] || process.env.AUDITLOG_SOURCE_CHECKOUT || process.env.AUDITLOG_SOURCE || pin.repository;
  // Git handles maintainer credentials; no credentials are written into the packaged archive.
  run('git', ['-C', origin, 'fetch', '--depth=1', source, pin.commit], root);
  const archive = join(cache, 'source.zip');
  run('git', ['-C', origin, 'archive', '--format=zip', '-o', archive, pin.commit], root);
  const checkout = join(cache, 'source');
  rmSync(checkout, {recursive: true, force: true});
  mkdirSync(checkout, {recursive: true});
  run(jar, ['xf', archive], checkout);
  const backend = join(checkout, 'backend');
  run(windows ? join(backend, 'mvnw.cmd') : 'sh',
    [...(windows ? [] : [join(backend, 'mvnw')]), '-B', '-DskipTests', '-Dproject.build.outputTimestamp=2026-01-01T00:00:00Z', 'package'], backend);
  const npm = join(tools, 'node/node_modules/npm/bin/npm-cli.js');
  const frontend = join(checkout, 'frontend');
  run(process.execPath, [npm, 'ci', '--no-audit', '--no-fund'], frontend);
  run(process.execPath, [npm, 'run', 'build-dev-server'], frontend);

  const staging = join(cache, 'bundle');
  rmSync(staging, {recursive: true, force: true});
  mkdirSync(staging, {recursive: true});
  cpSync(join(backend, 'target/auditlog.jar'), join(staging, 'auditlog.jar'));
  const dist = join(frontend, 'dist/fluxzero-auditlog');
  cpSync(join(dist, 'browser'), join(staging, 'ui'), {recursive: true});
  for (const [sourceFile, target] of [[join(checkout, 'LICENSE'), 'LICENSE.auditlog'],
    [join(dist, '3rdpartylicenses.txt'), 'THIRD_PARTY_UI.txt']]) {
    if (existsSync(sourceFile)) cpSync(sourceFile, join(staging, target));
  }
  if (!existsSync(join(staging, 'ui/index.html'))) throw new Error('Auditlog UI is missing index.html');
  const files = Object.fromEntries(filesUnder(staging).map(name => [name, sha(readFileSync(join(staging, name)))]));
  run(jar, ['--create', '--file', bundle, '--no-manifest', '--date=2026-01-01T00:00:00Z', '-C', staging, '.'], root);
  writeFileSync(manifestFile, JSON.stringify({sourceCommit: pin.commit, sha256: sha(readFileSync(bundle)), files}, null, 2) + '\n');
}
mkdirSync(output, {recursive: true});
cpSync(bundle, join(output, 'auditlog.zip'));
cpSync(manifestFile, join(output, 'manifest.json'));
console.log(`Packaged Auditlog ${pin.commit} with backend, UI and file checksums.`);
