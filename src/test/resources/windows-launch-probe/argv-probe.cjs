#!/usr/bin/env node
// The real npm cmd-shim wrappers forward to this receiver.
const fs = require('node:fs');
const args = process.argv.slice(2);
if (args.includes('--help')) {
  console.log('  --no-alt-screen\n  --tui-mode <mode>');
} else {
  fs.writeFileSync(process.env.IMUX_ARGV_OUTPUT, JSON.stringify(args), 'utf8');
}
