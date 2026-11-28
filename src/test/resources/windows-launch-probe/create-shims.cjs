// Run after installing the pinned cmd-shim dependency into the probe directory.
const path = require('node:path');
const fs = require('node:fs');
const root = path.resolve(process.argv[2]);
const shim = require(path.join(root, 'node_modules/cmd-shim'));
const source = path.resolve('src/test/resources/windows-launch-probe/argv-probe.cjs');
(async () => {
  for (const kind of ['npm-ps1', 'npm-cmd']) {
    const dir = path.join(root, kind);
    fs.mkdirSync(dir, { recursive: true });
    for (const cli of ['claude', 'codex', 'pi']) {
      await shim(source, path.join(dir, cli));
      if (kind === 'npm-cmd') fs.unlinkSync(path.join(dir, `${cli}.ps1`));
    }
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
