'use strict';
require('dotenv').config();
const crypto = require('crypto');
const { WbAccessToken, WbSession } = require('./db');

const LINK_SECRET = process.env.LINK_SECRET || '';

function hashToken(raw) {
    return crypto.createHmac('sha256', LINK_SECRET).update(raw).digest('hex');
}

async function generateAccessToken(telegram_id, ttlMs) {
    const raw       = crypto.randomBytes(32).toString('hex');
    const hash      = hashToken(raw);
    const expiresAt = new Date(Date.now() + ttlMs);
    await WbAccessToken.create({ hash, telegram_id, expiresAt, revoked: false });
    return { raw, hash, expiresAt };
}

async function revokeAllTokens(telegram_id) {
    await WbAccessToken.updateMany({ telegram_id, revoked: false }, { $set: { revoked: true } });
    await WbSession.deleteMany({ telegram_id });
}

async function validateAndActivateToken(raw) {
    if (!raw) return { ok: false, reason: 'no_token' };
    const hash = hashToken(raw);
    const now  = new Date();
    const token = await WbAccessToken.findOne(
        { hash, revoked: false, expiresAt: { $gt: now } }
    ).lean();
    if (!token) {
        const t = await WbAccessToken.findOne({ hash }).lean();
        if (!t)                return { ok: false, reason: 'not_found' };
        if (t.revoked)         return { ok: false, reason: 'revoked' };
        if (t.expiresAt < now) return { ok: false, reason: 'expired' };
        return { ok: false, reason: 'unknown' };
    }
    return { ok: true, telegram_id: token.telegram_id };
}

module.exports = { generateAccessToken, revokeAllTokens, validateAndActivateToken, hashToken };
