'use strict';
(async function () {
  var _mode = 'build';
  try {
    var r = await fetch('/api/session-info');
    if (r.ok) { var d = await r.json(); _mode = d.mode || 'build'; }
  } catch (_) {}

  if (_mode !== 'template') return;

  // Меняем UI
  function applyTemplateUi() {
    var topbar = document.querySelector('.topbar');
    if (topbar && !document.getElementById('tplModeLabel')) {
      var el = document.createElement('div');
      el.id = 'tplModeLabel';
      el.style.cssText = [
        'color:#a78bfa','font-size:12px','font-weight:700',
        'letter-spacing:.1em','text-transform:uppercase',
        'padding:2px 14px','background:rgba(167,139,250,.15)',
        'border-radius:20px','margin:0 12px','align-self:center',
        'white-space:nowrap','flex-shrink:0'
      ].join(';');
      el.textContent = 'Создание шаблона';
      var logo = topbar.querySelector('.topbar-logo');
      var nav  = topbar.querySelector('#projectTabs');
      if (logo && nav && logo.parentNode === nav.parentNode) {
        logo.parentNode.insertBefore(el, nav);
      } else { topbar.appendChild(el); }
    }
    var btnText = document.getElementById('buildBtnText');
    var btnIcon = document.getElementById('buildBtnIcon');
    if (btnText) btnText.textContent = 'СОЗДАТЬ ШАБЛОН';
    if (btnIcon) btnIcon.textContent = '📋';
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', applyTemplateUi);
  } else { applyTemplateUi(); }
  setTimeout(applyTemplateUi, 900);

  // Overlay при успехе
  function showSuccessOverlay(name) {
    var ov = document.getElementById('tplOverlay');
    if (ov) ov.remove();
    ov = document.createElement('div');
    ov.id = 'tplOverlay';
    ov.style.cssText = 'position:fixed;inset:0;background:rgba(13,17,23,.97);z-index:99999;' +
      'display:flex;flex-direction:column;align-items:center;justify-content:center;gap:20px;font-family:inherit';
    ov.innerHTML =
      '<div style="font-size:56px">✅</div>' +
      '<div style="color:#4ade80;font-size:1.5em;font-weight:700">Шаблон &laquo;' +
        name.replace(/</g,'&lt;').replace(/>/g,'&gt;') + '&raquo; сохранён!</div>' +
      '<div style="color:#6b7280;font-size:.95em">Теперь его можно использовать через «📋 Мои шаблоны» в боте</div>' +
      '<button id="tplOvBtn" style="margin-top:8px;padding:10px 28px;border-radius:8px;' +
        'background:#a78bfa;color:#fff;border:none;font-size:1em;cursor:pointer;font-weight:600">' +
        'Создать ещё один</button>';
    document.body.appendChild(ov);
    document.getElementById('tplOvBtn').onclick = function() { location.reload(); };
  }

  // Перехватываем fetch /api/build POST
  var _origFetch = window.fetch.bind(window);
  window.fetch = async function (url, opts) {
    var urlStr = typeof url === 'string' ? url : (url && url.url) || '';
    var method = (opts && opts.method || '').toUpperCase();

    if (urlStr.includes('/api/build') && method === 'POST') {
      var body = {};
      try { body = JSON.parse(opts.body || '{}'); } catch (_) {}

      var defName = 'Шаблон' + (body.prefix ? ' ' + body.prefix : '');
      var name = window.prompt('Название шаблона:', body.appName || defName);
      if (name === null) {
        return new Response(JSON.stringify({ error: 'cancelled' }), {
          status: 400, headers: { 'Content-Type': 'application/json' }
        });
      }

      try {
        var saveResp = await _origFetch('/api/templates', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            name:   name.trim() || defName,
            prefix: body.prefix || '',
            params: body
          })
        });

        if (saveResp.ok) {
          showSuccessOverlay(name.trim() || defName);
          // Возвращаем 200 с фейковым id — overlay закрывает всё визуально
          return new Response(JSON.stringify({ id: '__tpl_saved__' }), {
            status: 200, headers: { 'Content-Type': 'application/json' }
          });
        } else {
          var errData = await saveResp.json().catch(function() { return {}; });
          var msg = errData.error === 'template_limit_reached'
            ? 'Лимит 10 шаблонов исчерпан.'
            : ('Ошибка: ' + (errData.error || 'unknown'));
          window.alert(msg);
          return new Response(JSON.stringify({ error: errData.error }), {
            status: 400, headers: { 'Content-Type': 'application/json' }
          });
        }
      } catch (e) {
        window.alert('Ошибка сохранения: ' + e.message);
        return new Response(JSON.stringify({ error: e.message }), {
          status: 500, headers: { 'Content-Type': 'application/json' }
        });
      }
    }
    return _origFetch(url, opts);
  };
})();
