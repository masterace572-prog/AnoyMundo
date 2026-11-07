// Central configuration. Only SUPABASE_URL + SUPABASE_SERVICE_ROLE_KEY +
// AUTH_SECRET are required. Everything else has a safe default so the panel
// still boots on a fresh Vercel project before optional settings are added.

const env = process.env;

export const config = {
  supabaseUrl:
    env.SUPABASE_URL ||
    env.NEXT_PUBLIC_SUPABASE_URL ||
    env.SUPABASE_PROJECT_URL ||
    '',
  supabaseServiceKey:
    env.SUPABASE_SERVICE_ROLE_KEY ||
    env.SUPABASE_SERVICE_KEY ||
    env.SUPABASE_SECRET_KEY ||
    '',
  authSecret:
    env.AUTH_SECRET ||
    env.SESSION_SECRET ||
    // Deterministic dev fallback derived from the service key. Vercel will
    // always have AUTH_SECRET set; this only keeps `npm run dev` usable.
    (env.SUPABASE_SERVICE_ROLE_KEY ? `dev-${env.SUPABASE_SERVICE_ROLE_KEY.slice(-32)}` : 'local-dev-secret-change-me'),
  staticWords: env.STATIC_WORDS || env.LOADER_LICENSE_SALT || 'Vm8Lk7Uj2JmsjCPVPVjrLa7zgfx3uz9E',
  brand: env.BRAND_NAME || 'Anoy',
  // The single Owner account. owner-only routes are bound to this username as
  // well as level 1, so a stray level-1 row can never reach the owner panel.
  // The database enforces the same rule with a unique partial index.
  ownerUsername: String(env.OWNER_USERNAME || 'Anoy').trim(),
  telegramHandle: String(env.TELEGRAM_USERNAME || 'libAkAudioVisiual').replace(/^@/, ''),
  baseUrl: env.PUBLIC_BASE_URL || '',
  maxUploadMb: Number(env.MAX_LIB_UPLOAD_MB || 3),
  sessionMinutes: 30,
  rememberHours: 24,
  passwordSalt: 'XquxmymXDtWRA66D',
  license: 'Qp5KSGTquetnUkjX6UVBAURH8hTkZuLM',
  version: '2.0.0',
  games: { PUBG: 'PUBG Mobile' },
  durations: {
    2: '2 hours',
    5: '5 hours',
    24: '1 day',
    72: '3 days',
    168: '7 days',
    336: '14 days',
    720: '30 days',
  },
  // Short duration token used inside every licence key: USER-7D-AB12C.
  durationTokens: { 2: '2H', 5: '5H', 24: '1D', 72: '3D', 168: '7D', 336: '14D', 720: '30D' },
  // Random part of a generated key (A-Z, 0-9). Custom suffixes may be longer.
  keySuffixLength: 5,
  customSuffixMin: 4,
  customSuffixMax: 6,
  prices: { 2: 10, 5: 20, 24: 80, 72: 150, 168: 250, 336: 350, 720: 500 },
  accExpire: { 1: '1 Day', 7: '7 Days', 15: '15 Days', 30: '30 Days', 60: '60 Days' },
  accLevel: { 1: 'Owner', 2: 'Admin', 3: 'Reseller' },
};

export function envStatus() {
  const missing = [];
  if (!config.supabaseUrl) missing.push('SUPABASE_URL');
  if (!config.supabaseServiceKey) missing.push('SUPABASE_SERVICE_ROLE_KEY');
  if (!env.AUTH_SECRET && !env.SESSION_SECRET) missing.push('AUTH_SECRET (recommended)');
  return { ok: missing.length === 0, missing, url: config.supabaseUrl || null };
}
