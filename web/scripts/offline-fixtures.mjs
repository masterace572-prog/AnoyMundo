// Ephemeral OFFLINE test credentials. Never derived from a production account,
// committed as a usable password, written to screenshots, or printed in logs.
import crypto from 'node:crypto';

export const ownerPassword = process.env.MEMORY_OWNER_PASSWORD || `${crypto.randomBytes(24).toString('base64url')}a9`;
process.env.MEMORY_OWNER_PASSWORD = ownerPassword;
export function randomReferral() {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  return Array.from({ length: 7 }, () => alphabet[crypto.randomInt(alphabet.length)]).join('');
}
