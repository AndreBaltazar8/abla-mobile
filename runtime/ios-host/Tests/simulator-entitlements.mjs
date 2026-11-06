// Read linked Simulator entitlements, not the separate ad-hoc code signature.
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
export function extractEntitlements(bytes) {
  if (bytes.length < 32 || bytes.readUInt32LE(0) !== 0xfeedfacf || bytes.readUInt32LE(4) !== 0x0100000c) {
    throw new Error('expected thin arm64 little-endian Mach-O');
  }
  const count = bytes.readUInt32LE(16), end = 32 + bytes.readUInt32LE(20);
  if (count > 4096 || end > bytes.length) throw new Error('invalid load commands');
  let at = 32, found;
  const name = (offset) => bytes.subarray(offset, offset + 16).toString('ascii').split('\0')[0];
  for (let i = 0; i < count; i++) {
    if (at + 8 > end) throw new Error('truncated command');
    const command = bytes.readUInt32LE(at), size = bytes.readUInt32LE(at + 4);
    if (size < 8 || at + size > end) throw new Error('invalid command size');
    if (command === 0x19) {
      if (size < 72) throw new Error('truncated segment');
      const sections = bytes.readUInt32LE(at + 64);
      if (72 + sections * 80 > size) throw new Error('truncated section table');
      for (let j = 0; j < sections; j++) {
        const section = at + 72 + j * 80;
        if (name(section) !== '__entitlements' || name(section + 16) !== '__TEXT') continue;
        const length = bytes.readBigUInt64LE(section + 40), offset = bytes.readUInt32LE(section + 48);
        if (found || length > 1024n * 1024n || BigInt(offset) + length > BigInt(bytes.length)) {
          throw new Error('invalid or duplicate entitlements section');
        }
        found = bytes.subarray(offset, offset + Number(length));
      }
    }
    at += size;
  }
  if (!found) throw new Error('no linked Simulator entitlements');
  let length = found.length;
  while (length && found[length - 1] === 0) length--;
  return found.subarray(0, length);
}
if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const [input, output] = process.argv.slice(2);
  if (fs.statSync(input).size > 256 * 1024 * 1024) throw new Error('oversized fixture binary');
  fs.writeFileSync(output, extractEntitlements(fs.readFileSync(input)), { mode: 0o600 });
}
