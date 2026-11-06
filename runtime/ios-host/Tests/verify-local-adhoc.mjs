// Independent content-integrity check for the two local fixture bundles. This
// does NOT establish Apple policy, provisioning or physical-device acceptance.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
const [bundle, executable] = process.argv.slice(2);
if (!/^[A-Za-z0-9_-]+$/.test(executable || '')) throw new Error('invalid executable name');
const binary = fs.readFileSync(path.join(bundle, executable));
if (binary.length < 32 || binary.readUInt32LE(0) !== 0xfeedfacf) throw new Error('expected thin little-endian Mach-O');
const commands = binary.readUInt32LE(16), commandEnd = 32 + binary.readUInt32LE(20);
if (commands > 4096 || commandEnd > binary.length) throw new Error('invalid commands');
let at = 32, signature;
for (let i = 0; i < commands; i++) {
  if (at + 8 > commandEnd) throw new Error('truncated command');
  const command = binary.readUInt32LE(at), length = binary.readUInt32LE(at + 4);
  if (length < 8 || at + length > commandEnd) throw new Error('invalid command');
  if (command === 0x1d) {
    if (signature || length !== 16) throw new Error('invalid signature command');
    const offset = binary.readUInt32LE(at + 8), size = binary.readUInt32LE(at + 12);
    if (offset + size > binary.length) throw new Error('invalid signature extent');
    signature = { offset, bytes: binary.subarray(offset, offset + size) };
  }
  at += length;
}
if (!signature || signature.bytes.length < 12 || signature.bytes.readUInt32BE(0) !== 0xfade0cc0) throw new Error('missing embedded signature');
const container = signature.bytes, total = container.readUInt32BE(4), count = container.readUInt32BE(8);
if (total > container.length || count > 64 || 12 + count * 8 > total) throw new Error('invalid signature index');
if (container.subarray(total).some(x => x !== 0)) throw new Error('nonzero signature padding');
const slots = new Map();
for (let i = 0; i < count; i++) {
  const type = container.readUInt32BE(12 + i * 8), offset = container.readUInt32BE(16 + i * 8);
  if (slots.has(type) || offset < 12 + count * 8 || offset + 8 > total) throw new Error('invalid signature slot');
  const length = container.readUInt32BE(offset + 4);
  if (length < 8 || offset + length > total) throw new Error('invalid slot length');
  slots.set(type, container.subarray(offset, offset + length));
}
const cd = slots.get(0);
if (!cd || cd.length < 44 || cd.readUInt32BE(0) !== 0xfade0c02 || !(cd.readUInt32BE(12) & 2)) throw new Error('expected ad-hoc CodeDirectory');
const hashOffset = cd.readUInt32BE(16), special = cd.readUInt32BE(24), pages = cd.readUInt32BE(28);
const limit = cd.readUInt32BE(32), hashSize = cd[36], hashType = cd[37], exponent = cd[39];
if (hashSize !== 32 || hashType !== 2 || exponent > 20 || special > 16 ||
    hashOffset < 44 + special * hashSize || hashOffset + pages * hashSize > cd.length || limit !== signature.offset) {
  throw new Error('unsupported or invalid CodeDirectory shape');
}
const pageSize = exponent ? 2 ** exponent : limit;
if (!pageSize || pages !== Math.ceil(limit / pageSize)) throw new Error('invalid code page count');
const digest = value => crypto.createHash('sha256').update(value).digest();
for (let i = 0; i < pages; i++) {
  const expected = cd.subarray(hashOffset + i * hashSize, hashOffset + (i + 1) * hashSize);
  if (!digest(binary.subarray(i * pageSize, Math.min(limit, (i + 1) * pageSize))).equals(expected)) throw new Error(`code page ${i} mismatch`);
}
for (let i = 1; i <= special; i++) {
  const expected = cd.subarray(hashOffset - i * hashSize, hashOffset - (i - 1) * hashSize);
  const value = i === 1 ? fs.readFileSync(path.join(bundle, 'Info.plist')) :
    i === 3 ? fs.readFileSync(path.join(bundle, '_CodeSignature/CodeResources')) : slots.get(i);
  if (value ? !digest(value).equals(expected) : expected.some(x => x !== 0)) throw new Error(`special slot ${i} mismatch`);
}
const allowed = new Set([executable, 'Info.plist', '_CodeSignature']);
if (fs.readdirSync(bundle).some(name => !allowed.has(name))) throw new Error('unexpected resource: this checker covers minimal fixtures only');
console.log(`${executable}: ${pages} SHA256 code pages and ${special} special-slot hashes match; ad-hoc only, not Apple policy acceptance`);
