'use strict';
require('dotenv').config();
const axios = require('axios');

const PANEL_URL    = process.env.PANEL_API_URL   || 'http://157.22.190.49:8080';
const ADMIN_LOGIN  = process.env.PANEL_ADMIN_LOGIN    || 'Fantazer';
const ADMIN_PASS   = process.env.PANEL_ADMIN_PASSWORD || 'viking651241';
const TOKEN_TTL_MS = 23 * 60 * 60 * 1000; // 23 часа

let cachedToken  = null;
let tokenExpiry  = 0;
let refreshTimer = null;

async function login() {
    const res = await axios.post(PANEL_URL + '/api/auth/login', {
        login: ADMIN_LOGIN, password: ADMIN_PASS
    }, { timeout: 10000 });
    cachedToken = res.data.token;
    tokenExpiry = Date.now() + TOKEN_TTL_MS;
    if (refreshTimer) clearTimeout(refreshTimer);
    refreshTimer = setTimeout(async () => {
        try { await login(); } catch (e) { console.error('[panelApiCache] refresh failed:', e.message); }
    }, TOKEN_TTL_MS - 5 * 60 * 1000);
    return cachedToken;
}

async function getToken() {
    if (cachedToken && Date.now() < tokenExpiry) return cachedToken;
    return login();
}

async function panelRequest(method, path, body) {
    const token = await getToken();
    try {
        const res = await axios({ method, url: PANEL_URL + path, data: body,
            headers: { Authorization: 'Bearer ' + token }, timeout: 10000 });
        return res.data;
    } catch (e) {
        if (e.response && e.response.status === 401) {
            cachedToken = null;
            const token2 = await login();
            const res2 = await axios({ method, url: PANEL_URL + path, data: body,
                headers: { Authorization: 'Bearer ' + token2 }, timeout: 10000 });
            return res2.data;
        }
        throw e;
    }
}

async function panelAdminGetUserByLogin(login) {
    try {
        const data = await panelRequest('get', '/api/admin/users');
        const users = Array.isArray(data) ? data : (data.users || []);
        return users.find(u => u.login === login) || null;
    } catch (e) {
        console.error('[panelApiCache] getAllUsers error:', e.message);
        return null;
    }
}

async function init() {
    try { await login(); console.log('[panelApiCache] logged in OK'); }
    catch (e) { console.error('[panelApiCache] init failed:', e.message); }
}

module.exports = { init, panelAdminGetUserByLogin };
