// Exactly two release channels. Licence keys, pricing and server settings stay shared.
// Authority comes from the DB-protected owner identity, never display name or form flags.
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

/** The unique, immutable original Owner is above the delegated Kenny Owner. */
export function isRealOwner(user) {
  return ownerChannel(user) === 'anoy';
}

export function managedChannels(user) {
  const own = ownerChannel(user);
  if (!own || Number(user?.status) !== 1) return [];
  return isRealOwner(user) ? ['anoy', 'kenny'] : [own];
}

/**
 * Anoy may explicitly select either fixed channel; Kenny can select only Kenny.
 * Missing selectors retain the actor's own channel for old scripts. Conflicting,
 * duplicate, null, array, object and unknown selectors always fail closed.
 */
export function assertWriteChannel(user, body = {}, query = null) {
  const allowed = managedChannels(user);
  if (!allowed.length || !body || typeof body !== 'object' || Array.isArray(body)) return null;
  const selectors = [];
  for (const key of ['channel', 'owner_channel']) {
    if (Object.hasOwn(body, key)) selectors.push(body[key]);
  }
  const queryValues = query?.getAll?.('channel') || [];
  if (queryValues.length > 1) return null;
  selectors.push(...queryValues);
  if (selectors.some(value => typeof value !== 'string' || !Object.hasOwn(CHANNELS, value))) return null;
  const selected = selectors.length ? selectors[0] : ownerChannel(user);
  return selectors.every(value => value === selected) && allowed.includes(selected) ? selected : null;
}

export function releasePageContext(user, query, path) {
  const channel = assertWriteChannel(user, {}, query);
  if (!channel) return null;
  return {
    releaseChannel: channel,
    releaseBrand: CHANNELS[channel].name,
    releasePath: path,
    releaseChoices: isRealOwner(user) ? managedChannels(user).map(id => ({
      id, name: CHANNELS[id].name, selected: id === channel,
    })) : null,
  };
}
