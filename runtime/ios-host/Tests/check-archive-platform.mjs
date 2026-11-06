import fs from 'node:fs';
const [file, target] = process.argv.slice(2);
if (!['ios-arm64', 'ios-arm64-simulator'].includes(target)) throw new Error('unsupported archive target');
const expected = target === 'ios-arm64-simulator' ? 'iossimulator' : 'ios';
const records = fs.readFileSync(file, 'utf8').split(/Load command \d+/).filter(x => /LC_BUILD_VERSION/.test(x));
if (!records.length || records.some(x => !new RegExp(`platform\\s+${expected}\\b`).test(x) || !/minos\s+17(?:\.0){1,2}\b/.test(x))) {
  throw new Error('archive platform/minimum-version metadata mismatch');
}
console.log(`${target}: ${records.length} object platform records agree, minimum iOS17`);
