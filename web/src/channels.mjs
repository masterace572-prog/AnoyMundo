// Exactly two release channels. Licence keys, pricing and server settings stay shared.
// Never derive a write channel from a request parameter or an editable display name.
export const CHANNELS = Object.freeze({
  anoy: Object.freeze({ id: 'anoy', name: 'Anoy', packageName: 'com.xlox', apkName: 'Anoy-XLOX-loader.apk' }),
  kenny: Object.freeze({ id: 'kenny', name: 'KennyPvt', packageName: 'com.kennypvt', apkName: 'KennyPvt-loader.apk' }),
});

export function publicChannel(query) {
  if ((query?.getAll?.('channel') || []).length > 1) return null;
  const value = query?.get?.('channel');
  return value === null || value === undefined || value === '' ? 'anoy' : (Object.hasOwn(CHANNELS, value) ? value : null);
}

export function ownerChannel(user) {
  return Number(user?.level) === 1 && Object.hasOwn(CHANNELS, user?.owner_channel || '') ? user.owner_channel : null;
}

export function assertWriteChannel(user, body = {}) {
  const channel = ownerChannel(user);
  if (!channel || (body.channel !== undefined && body.channel !== channel)
      || (body.owner_channel !== undefined && body.owner_channel !== channel)) return null;
  return channel;
}
