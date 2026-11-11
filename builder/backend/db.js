'use strict';
require('dotenv').config();
const mongoose = require('mongoose');

mongoose.connect(process.env.MONGO_URI || 'mongodb://127.0.0.1:27017/fantasy_hub', {
    serverSelectionTimeoutMS: 5000,
}).then(() => {
    console.log('[DB] MongoDB connected');
    // TTL-индексы
    mongoose.connection.db.collection('wb_access_tokens').createIndex({ expiresAt: 1 }, { expireAfterSeconds: 86400 }).catch(() => {});
    mongoose.connection.db.collection('wb_sessions').createIndex({ expiresAt: 1 }, { expireAfterSeconds: 0 }).catch(() => {});
}).catch(e => console.error('[DB] MongoDB error:', e.message));

// 1. Токены доступа
const accessTokenSchema = new mongoose.Schema({
    hash:        { type: String, required: true, unique: true, index: true },
    telegram_id: { type: Number, required: true, index: true },
    expiresAt:   { type: Date, required: true },
    // activated removed - link reusable within TTL
    revoked:     { type: Boolean, default: false },
    createdAt:   { type: Date, default: Date.now }
}, { collection: 'wb_access_tokens', strict: false });

// 2. Активные сессии
const sessionSchema = new mongoose.Schema({
    sessionId:     { type: String, required: true, unique: true, index: true },
    telegram_id:   { type: Number, required: true, index: true },
    expiresAt:     { type: Date, required: true },
    activeBuildId: { type: String, default: null },
    mode:          { type: String, default: 'build' },
    createdAt:     { type: Date, default: Date.now }
}, { collection: 'wb_sessions', strict: false });

// 3. История сборок
const buildHistorySchema = new mongoose.Schema({
    telegram_id:     { type: Number, required: true, index: true },
    prefix:          { type: String },
    buildId:         { type: String, required: true, unique: true, index: true },
    params:          { type: mongoose.Schema.Types.Mixed },
    iconRef:         { type: String, default: null },
  dropperIconRef: { type: String, default: null },
    status:          { type: String, default: 'running' }, // running / success / failed
    filename:        { type: String, default: null },
    apkPath:         { type: String, default: null },
    downloadToken:   { type: String, default: null },
    downloadExpires: { type: Date,   default: null },
    createdAt:       { type: Date,   default: Date.now },
    finishedAt:      { type: Date,   default: null }
}, { collection: 'wb_build_history', strict: false });

// 4. Шаблоны (лимит 10 на telegram_id)
const templateSchema = new mongoose.Schema({
    telegram_id:  { type: Number, required: true, index: true },
    templateId:   { type: String, required: true, unique: true, index: true },
    name:         { type: String, required: true },
    prefix:       { type: String, default: '' },
    params:       { type: mongoose.Schema.Types.Mixed },
    iconRef:      { type: String, default: null },
    createdAt:    { type: Date, default: Date.now },
    updatedAt:    { type: Date, default: Date.now }
}, { collection: 'wb_templates', strict: false });

// Бот-коллекция users — только для чтения из webbuilder
const wbUserSchema = new mongoose.Schema({
    telegram_id:           { type: Number, index: true },
    login:                 String,
    is_allowed:            Number,
    subscription_expires_at: String,
    panel_user_id:         String,
}, { collection: 'users', strict: false });

const WbAccessToken  = mongoose.model('WbAccessToken',  accessTokenSchema);
const WbSession      = mongoose.model('WbSession',      sessionSchema);
const WbBuildHistory = mongoose.model('WbBuildHistory', buildHistorySchema);
const WbTemplate     = mongoose.model('WbTemplate',     templateSchema);
const WbUser         = mongoose.model('WbUser',         wbUserSchema);

module.exports = { WbAccessToken, WbSession, WbBuildHistory, WbTemplate, WbUser };
