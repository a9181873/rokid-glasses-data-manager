package tw.dky.rokidfiles.share;

import java.nio.charset.StandardCharsets;

/** 內嵌、無 CDN、無追蹤碼的管理介面資源。 */
final class WebAssets {
    static final byte[] INDEX = bytes("""
            <!doctype html>
            <html lang="zh-Hant-TW">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
              <meta name="color-scheme" content="light">
              <title>Rokid眼鏡檔案管理APP</title>
              <link rel="stylesheet" href="/app.css">
              <script src="/app.js" defer></script>
            </head>
            <body>
              <header class="topbar">
                <div>
                  <p class="eyebrow">ROKID GLASSES</p>
                  <h1>Rokid眼鏡檔案管理APP</h1>
                </div>
                <span class="local-badge">只在本機</span>
              </header>

              <main>
                <section id="pair-panel" class="panel narrow" aria-labelledby="pair-title">
                  <h2 id="pair-title">輸入眼鏡上的 6 位 PIN</h2>
                  <p>PIN 顯示在眼鏡的分享通知。每次啟動都會更換。</p>
                  <form id="pair-form" autocomplete="off">
                    <label for="pin">分享 PIN</label>
                    <input id="pin" name="pin" type="text" inputmode="numeric" pattern="[0-9]{6}"
                           minlength="6" maxlength="6" placeholder="000000" required autofocus>
                    <button class="primary" type="submit">配對並進入</button>
                  </form>
                  <p id="pair-error" class="error" role="alert" hidden></p>
                  <p class="warning pair-warning"><strong>USB 連線最安全，建議優先使用。</strong>
                    Wi‑Fi 僅限自己的手機熱點或可信任網路；HTTP 與 PIN 不會加密檔案內容。</p>
                </section>

                <section id="manager" hidden>
                  <aside class="warning" aria-label="網路安全提醒">
                    <strong>USB 連線最安全，建議優先使用。</strong>
                    Wi‑Fi 分享僅限自己的手機熱點或可信任網路。此頁使用 HTTP；PIN 只限制存取，
                    不會加密傳輸內容，請勿在公共 Wi‑Fi 使用。
                  </aside>

                  <section class="remote panel" aria-labelledby="remote-title">
                    <div class="remote-head">
                      <h2 id="remote-title">眼鏡四鍵遙控</h2>
                      <span>直接取代難操作的觸控板</span>
                    </div>
                    <div class="remote-grid">
                      <button type="button" data-remote="previous" aria-label="上一個">← 上一個</button>
                      <button type="button" data-remote="next" aria-label="下一個">下一個 →</button>
                      <button type="button" data-remote="open" class="primary">開啟／確認</button>
                      <button type="button" data-remote="back">返回</button>
                    </div>
                  </section>

                  <div class="toolbar panel">
                    <div class="toolbar-row">
                      <label class="search-wrap" for="search">
                        <span>搜尋</span>
                        <input id="search" type="search" placeholder="檔名">
                      </label>
                      <label class="upload primary" for="upload">＋ 上傳檔案</label>
                      <input id="upload" type="file" accept="image/*,video/*" multiple hidden>
                      <button id="refresh" type="button">重新整理</button>
                    </div>
                    <div class="filter-block">
                      <p class="control-label">資料檢視</p>
                      <div class="filters" role="group" aria-label="資料檢視">
                        <button type="button" data-view="all" aria-pressed="true">全部</button>
                        <button type="button" data-view="today" aria-pressed="false">今日</button>
                        <button type="button" data-view="date" aria-pressed="false">指定日期</button>
                        <button type="button" data-view="large" aria-pressed="false">大檔 100 MB＋</button>
                        <button type="button" data-view="favorites" aria-pressed="false">最愛</button>
                        <button type="button" data-view="protected" aria-pressed="false">已保護</button>
                        <button type="button" data-view="trash" aria-pressed="false">垃圾桶</button>
                        <button type="button" data-view="duplicates" aria-pressed="false">重複檔</button>
                      </div>
                    </div>
                    <form id="date-tools" class="context-tools" hidden>
                      <label for="date-filter">選擇日期</label>
                      <input id="date-filter" type="date" required>
                      <button class="primary" type="submit">顯示這一天</button>
                    </form>
                    <div id="duplicate-tools" class="context-tools" hidden>
                      <span>結果是掃描當下的快照；檔案變更後請重新掃描。</span>
                      <button id="duplicate-scan" class="primary" type="button">開始掃描重複檔</button>
                    </div>
                    <div class="filter-block">
                      <p class="control-label">檔案類型</p>
                      <div class="filters" role="group" aria-label="檔案類型">
                      <button type="button" data-kind="all" aria-pressed="true">全部</button>
                      <button type="button" data-kind="photo" aria-pressed="false">照片</button>
                      <button type="button" data-kind="video" aria-pressed="false">影片</button>
                      </div>
                    </div>
                    <div id="upload-state" class="upload-state" hidden>
                      <span id="upload-label">正在上傳</span>
                      <progress id="upload-progress" max="100" value="0"></progress>
                    </div>
                  </div>

                  <div class="result-head">
                    <p id="count" aria-live="polite">載入中…</p>
                    <p id="status" class="status" role="status"></p>
                  </div>
                  <div id="files" class="files" aria-live="polite"></div>
                  <div id="empty" class="panel empty" hidden>
                    <strong id="empty-title">沒有符合的檔案</strong>
                    <span id="empty-help">可調整搜尋條件，或從手機／電腦直接上傳。</span>
                  </div>
                </section>
              </main>
              <footer>資料不經雲端；停止眼鏡上的分享服務後，連線憑證立即失效。</footer>
            </body>
            </html>
            """);

    static final byte[] CSS = bytes("""
            :root {
              color-scheme: light;
              --bg: #f1f4ef;
              --surface: #fffdfa;
              --surface-2: #e8efe8;
              --text: #26352c;
              --muted: #68766d;
              --line: #c9d6cb;
              --bright: #5f806a;
              --bright-strong: #42614d;
              --danger: #a45f5f;
              --focus: #315a42;
              --warning: #f6eedf;
              --shadow: 0 .3rem 1.2rem #35483d18;
              font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
            }
            * { box-sizing: border-box; }
            [hidden] { display: none !important; }
            body {
              margin: 0;
              min-height: 100vh;
              background: linear-gradient(180deg, #e7eee7 0, var(--bg) 24rem);
              color: var(--text);
              line-height: 1.5;
            }
            button, input, .upload { font: inherit; touch-action: manipulation; }
            button, .upload {
              min-height: 3rem;
              border: 1px solid var(--line);
              border-radius: .8rem;
              background: var(--surface-2);
              color: var(--text);
              padding: .58rem .9rem;
              cursor: pointer;
              font-weight: 700;
            }
            button:hover, .upload:hover { border-color: var(--bright); background: #dee9df; }
            button:active, .upload:active { transform: translateY(1px); }
            button:disabled { cursor: not-allowed; opacity: .55; }
            button:focus-visible, input:focus-visible, .upload:focus-within, a:focus-visible {
              outline: 3px solid var(--focus);
              outline-offset: 2px;
            }
            button.primary, .primary {
              background: var(--bright);
              border-color: var(--bright);
              color: #fff;
            }
            .topbar {
              display: flex;
              align-items: center;
              justify-content: space-between;
              gap: 1rem;
              max-width: 78rem;
              margin: auto;
              padding: calc(1rem + env(safe-area-inset-top)) 1rem 1rem;
              border-bottom: 1px solid var(--line);
              background: #f8faf6dd;
              backdrop-filter: blur(10px);
            }
            h1, h2, p { margin-top: 0; }
            h1 { margin-bottom: 0; font-size: clamp(1.65rem, 5vw, 2.35rem); line-height: 1.05; }
            h2 { font-size: 1.25rem; }
            .eyebrow { margin-bottom: .2rem; color: var(--bright-strong); font-size: .72rem; letter-spacing: .16em; }
            .local-badge {
              border: 1px solid var(--bright);
              border-radius: 99rem;
              color: var(--bright-strong);
              padding: .35rem .7rem;
              white-space: nowrap;
              font-size: .82rem;
              font-weight: 800;
            }
            main { max-width: 78rem; min-height: 65vh; margin: 0 auto; padding: 1rem; }
            .panel {
              background: var(--surface);
              border: 1px solid var(--line);
              border-radius: 1.1rem;
              padding: 1.05rem;
              box-shadow: var(--shadow);
            }
            .narrow { max-width: 32rem; margin: 3rem auto; }
            #pair-form { display: grid; gap: .65rem; }
            #pin {
              width: 100%;
              min-height: 3.5rem;
              border: 2px solid var(--line);
              border-radius: .65rem;
              background: #fff;
              color: var(--bright-strong);
              padding: .5rem 1rem;
              text-align: center;
              font-size: 1.8rem;
              font-weight: 800;
              letter-spacing: .34em;
            }
            .error { color: var(--danger); margin: .8rem 0 0; font-weight: 700; }
            .warning {
              margin-bottom: 1rem;
              padding: .8rem 1rem;
              border-left: .35rem solid var(--bright);
              background: var(--warning);
              color: var(--muted);
            }
            .warning strong { color: var(--text); }
            .pair-warning { margin: 1rem 0 0; font-size: .86rem; }
            .toolbar { display: grid; gap: .8rem; }
            .remote {
              position: sticky;
              top: .5rem;
              z-index: 5;
              display: grid;
              grid-template-columns: minmax(9rem, auto) 1fr;
              gap: .8rem;
              align-items: center;
              margin-bottom: 1rem;
              box-shadow: 0 .45rem 1.4rem #35483d24;
            }
            .remote-head h2 { margin: 0; }
            .remote-head span { color: var(--muted); font-size: .82rem; }
            .remote-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: .55rem; }
            .remote-grid button { min-height: 3.65rem; font-size: 1rem; }
            .toolbar-row { display: flex; align-items: end; flex-wrap: wrap; gap: .65rem; }
            .search-wrap { display: grid; flex: 1 1 14rem; gap: .25rem; color: var(--muted); }
            .search-wrap input {
              min-height: 2.85rem;
              width: 100%;
              border: 1px solid var(--line);
              border-radius: .65rem;
              background: #fff;
              color: var(--text);
              padding: .55rem .75rem;
            }
            .upload { display: inline-flex; align-items: center; justify-content: center; }
            .filter-block { display: grid; gap: .35rem; }
            .control-label { margin: 0; color: var(--muted); font-size: .86rem; font-weight: 700; }
            .filters { display: flex; gap: .45rem; overflow-x: auto; padding: .1rem 0 .35rem; scrollbar-width: thin; }
            .filters button { flex: 0 0 auto; min-height: 2.65rem; border-radius: 99rem; }
            .filters [aria-pressed="true"] { border-color: var(--bright); background: #dce9df; color: var(--bright-strong); }
            .context-tools {
              display: flex;
              align-items: center;
              flex-wrap: wrap;
              gap: .6rem;
              padding: .75rem;
              border: 1px solid var(--line);
              border-radius: .7rem;
              background: #f7f9f5;
              color: var(--muted);
            }
            .context-tools input {
              min-height: 2.85rem;
              border: 1px solid var(--line);
              border-radius: .65rem;
              background: #fff;
              color: var(--text);
              padding: .55rem .75rem;
            }
            .upload-state { display: grid; grid-template-columns: minmax(8rem, auto) 1fr; align-items: center; gap: .8rem; }
            progress { width: 100%; accent-color: var(--bright); }
            .result-head { display: flex; justify-content: space-between; gap: 1rem; margin: 1rem 0 .55rem; color: var(--muted); }
            .result-head p { margin: 0; }
            .status { color: var(--bright-strong); text-align: right; }
            .status.is-error { color: var(--danger); }
            .files { display: grid; grid-template-columns: repeat(auto-fill, minmax(16rem, 1fr)); gap: .8rem; }
            .file-card {
              min-width: 0;
              overflow: hidden;
              border: 1px solid var(--line);
              border-radius: 1rem;
              background: var(--surface);
              box-shadow: var(--shadow);
            }
            .file-card.is-protected { border-color: var(--bright); }
            .thumb-wrap {
              display: grid;
              place-items: center;
              aspect-ratio: 16 / 10;
              overflow: hidden;
              background: #e5ebe5;
              color: var(--muted);
              font-size: 2rem;
            }
            .thumb { width: 100%; height: 100%; object-fit: cover; }
            .file-body { padding: .9rem; }
            .file-name { margin: 0 0 .25rem; overflow-wrap: anywhere; font-weight: 800; }
            .meta { margin: 0; color: var(--muted); font-size: .86rem; }
            .badges { display: flex; flex-wrap: wrap; gap: .35rem; margin: 0 0 .5rem; }
            .badge {
              border: 1px solid var(--line);
              border-radius: 99rem;
              padding: .18rem .5rem;
              color: var(--muted);
              font-size: .76rem;
              font-weight: 800;
            }
            .badge.active { border-color: var(--bright); background: #e2ece3; color: var(--bright-strong); }
            .badge.trash { border-color: var(--danger); color: var(--danger); }
            .actions { display: grid; grid-template-columns: 1fr 1fr; gap: .45rem; margin-top: .7rem; }
            .actions a {
              display: flex;
              min-height: 2.65rem;
              align-items: center;
              justify-content: center;
              border: 1px solid var(--line);
              border-radius: .6rem;
              color: var(--text);
              text-decoration: none;
              font-weight: 700;
              background: var(--surface-2);
            }
            .actions .danger { color: var(--danger); }
            .actions .wide { grid-column: 1 / -1; }
            .duplicate-groups { display: grid; gap: 1rem; }
            .duplicate-group {
              border: 1px solid var(--line);
              border-radius: .9rem;
              padding: .85rem;
              background: #f7f9f5;
            }
            .duplicate-head {
              display: flex;
              justify-content: space-between;
              gap: .8rem;
              margin-bottom: .7rem;
            }
            .duplicate-head h2, .duplicate-head p { margin: 0; }
            .duplicate-head p { color: var(--muted); text-align: right; }
            .duplicate-files {
              display: grid;
              grid-template-columns: repeat(auto-fill, minmax(16rem, 1fr));
              gap: .8rem;
            }
            .empty { display: grid; justify-items: center; gap: .25rem; padding: 3rem 1rem; color: var(--muted); }
            .empty strong { color: var(--text); }
            footer { max-width: 78rem; margin: 1rem auto; padding: 1rem; color: var(--muted); font-size: .82rem; }
            @media (max-width: 36rem) {
              main { padding: .7rem; }
              .topbar { padding-left: .8rem; padding-right: .8rem; }
              .topbar h1 { font-size: 1.55rem; }
              .local-badge { font-size: .74rem; }
              .narrow { margin-top: 1.2rem; }
              .toolbar-row > button, .toolbar-row > .upload { flex: 1 1 8rem; }
              .files { grid-template-columns: 1fr; }
              .duplicate-files { grid-template-columns: 1fr; }
              .duplicate-head { display: grid; }
              .duplicate-head p { text-align: left; }
              .context-tools > * { flex: 1 1 10rem; }
              .remote { position: sticky; top: .35rem; grid-template-columns: 1fr; padding: .8rem; }
              .remote-head { display: flex; align-items: baseline; justify-content: space-between; gap: .5rem; }
              .remote-head h2 { font-size: 1.05rem; }
              .remote-grid { grid-template-columns: 1fr 1fr; }
              .remote-grid button { min-height: 3.2rem; }
              .toolbar { gap: .7rem; }
              .actions button, .actions a { min-height: 3rem; }
            }
            @media (prefers-reduced-motion: reduce) { * { scroll-behavior: auto !important; } }
            """);

    static final byte[] JS = bytes("""
            'use strict';
            (() => {
              const byId = (id) => document.getElementById(id);
              const pairPanel = byId('pair-panel');
              const manager = byId('manager');
              const pairForm = byId('pair-form');
              const pairError = byId('pair-error');
              const filesNode = byId('files');
              const emptyNode = byId('empty');
              const emptyTitle = byId('empty-title');
              const emptyHelp = byId('empty-help');
              const countNode = byId('count');
              const statusNode = byId('status');
              const searchNode = byId('search');
              const dateTools = byId('date-tools');
              const dateInput = byId('date-filter');
              const duplicateTools = byId('duplicate-tools');
              const duplicateScan = byId('duplicate-scan');
              const viewLabels = Object.freeze({
                all: '全部',
                today: '今日',
                date: '指定日期',
                large: '大檔',
                favorites: '最愛',
                protected: '已保護',
                trash: '垃圾桶',
                duplicates: '重複檔'
              });
              const validViews = new Set(Object.keys(viewLabels));
              let csrf = '';
              let items = [];
              let duplicateGroups = [];
              let duplicateScanned = false;
              let duplicatesDirty = false;
              let selectedKind = 'all';
              let selectedView = 'all';
              let selectedDate = '';
              let loadError = '';
              let loadSequence = 0;

              const safeJson = async (response) => {
                let data = {};
                try { data = await response.json(); } catch (_) { /* 使用通用錯誤 */ }
                if (!response.ok) {
                  const error = new Error(data.error || `要求失敗（${response.status}）`);
                  error.status = response.status;
                  error.retryAfter = response.headers.get('Retry-After');
                  throw error;
                }
                return data;
              };

              const api = async (path, options = {}) => {
                const headers = new Headers(options.headers || {});
                if (csrf) headers.set('X-Rokid-CSRF', csrf);
                const response = await fetch(path, {...options, headers, cache: 'no-store'});
                if (response.status === 401) showPair('連線憑證已失效，請重新輸入 PIN。');
                return safeJson(response);
              };

              const postJson = (path, body) => api(path, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(body)
              });

              const setStatus = (message, isError = false) => {
                statusNode.textContent = message;
                statusNode.classList.toggle('is-error', isError);
              };

              const showPair = (message = '') => {
                csrf = '';
                loadSequence += 1;
                manager.hidden = true;
                pairPanel.hidden = false;
                pairError.textContent = message;
                pairError.hidden = !message;
                byId('pin').focus();
              };

              const showManager = () => {
                pairPanel.hidden = true;
                manager.hidden = false;
              };

              const localDateValue = (date) => {
                const year = date.getFullYear();
                const month = String(date.getMonth() + 1).padStart(2, '0');
                const day = String(date.getDate()).padStart(2, '0');
                return `${year}-${month}-${day}`;
              };

              const resetSessionUi = () => {
                loadSequence += 1;
                selectedView = 'all';
                selectedDate = '';
                items = [];
                duplicateGroups = [];
                duplicateScanned = false;
                duplicatesDirty = false;
                loadError = '';
                updateViewControls();
              };

              pairForm.addEventListener('submit', async (event) => {
                event.preventDefault();
                pairError.hidden = true;
                const pin = byId('pin').value.trim();
                if (!/^[0-9]{6}$/.test(pin)) {
                  showPair('請輸入 6 位數字 PIN。');
                  return;
                }
                const submit = pairForm.querySelector('button');
                submit.disabled = true;
                try {
                  const data = await safeJson(await fetch('/api/pair', {
                    method: 'POST',
                    cache: 'no-store',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({pin})
                  }));
                  csrf = data.csrf;
                  byId('pin').value = '';
                  resetSessionUi();
                  showManager();
                  await loadCurrentView();
                } catch (error) {
                  const wait = error.retryAfter ? ` 請等待 ${error.retryAfter} 秒。` : '';
                  showPair(error.message + wait);
                } finally {
                  submit.disabled = false;
                }
              });

              const formatBytes = (size) => {
                if (!Number.isFinite(size) || size < 0) return '未知大小';
                const units = ['B', 'KB', 'MB', 'GB', 'TB'];
                let value = size;
                let unit = 0;
                while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit += 1; }
                return `${value.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`;
              };

              const formatDate = (epoch) => {
                if (!epoch) return '日期未知';
                try {
                  return new Intl.DateTimeFormat('zh-TW', {
                    dateStyle: 'medium',
                    timeStyle: 'short'
                  }).format(new Date(epoch));
                } catch (_) {
                  return new Date(epoch).toLocaleString();
                }
              };

              const formatDuration = (milliseconds) => {
                if (!Number.isFinite(milliseconds) || milliseconds <= 0) return '';
                const totalSeconds = Math.round(milliseconds / 1000);
                const minutes = Math.floor(totalSeconds / 60);
                const seconds = String(totalSeconds % 60).padStart(2, '0');
                return `${minutes}:${seconds}`;
              };

              const kindLabel = (kind) => {
                if (kind === 'video') return '影片';
                if (kind === 'photo') return '照片';
                return '檔案';
              };

              const actionButton = (label, className, task, options = {}) => {
                const node = document.createElement('button');
                node.type = 'button';
                node.textContent = label;
                if (className) node.className = className;
                if (options.title) node.title = options.title;
                node.disabled = Boolean(options.disabled);
                if (!node.disabled) {
                  node.addEventListener('click', async () => {
                    const original = node.textContent;
                    node.disabled = true;
                    node.textContent = '處理中…';
                    try {
                      await task();
                    } catch (error) {
                      if (error.status !== 401) setStatus(error.message, true);
                    } finally {
                      node.disabled = Boolean(options.disabled);
                      node.textContent = original;
                    }
                  });
                }
                return node;
              };

              const badge = (label, className = '') => {
                const node = document.createElement('span');
                node.className = `badge ${className}`.trim();
                node.textContent = label;
                return node;
              };

              const isTrashItem = (item) => selectedView === 'trash' || item.trashed === true;

              const renderCard = (item) => {
                const card = document.createElement('article');
                card.className = item.protected ? 'file-card is-protected' : 'file-card';

                const thumbWrap = document.createElement('div');
                thumbWrap.className = 'thumb-wrap';
                thumbWrap.textContent = kindLabel(item.kind);
                const img = document.createElement('img');
                img.className = 'thumb';
                img.loading = 'lazy';
                img.alt = '';
                img.src = `/api/thumb?id=${encodeURIComponent(item.id)}`;
                img.addEventListener('load', () => {
                  thumbWrap.textContent = '';
                  thumbWrap.append(img);
                }, {once: true});
                img.addEventListener('error', () => img.remove(), {once: true});
                card.append(thumbWrap);

                const body = document.createElement('div');
                body.className = 'file-body';
                const badges = document.createElement('div');
                badges.className = 'badges';
                badges.append(badge(kindLabel(item.kind)));
                if (item.favorite) badges.append(badge('★ 最愛', 'active'));
                if (item.protected) badges.append(badge('已保護', 'active'));
                if (isTrashItem(item)) badges.append(badge('垃圾桶', 'trash'));
                if (item.canRename !== true && item.canTrash !== true
                    && item.canRestore !== true) {
                  badges.append(badge('檔案唯讀'));
                }
                const name = document.createElement('p');
                name.className = 'file-name';
                name.textContent = item.name;
                name.title = item.name;
                const meta = document.createElement('p');
                meta.className = 'meta';
                const duration = formatDuration(item.duration);
                meta.textContent = `${formatBytes(item.size)} · ${formatDate(item.modified)}`
                  + (duration ? ` · ${duration}` : '');
                body.append(badges, name, meta);

                const actions = document.createElement('div');
                actions.className = 'actions';
                if (isTrashItem(item)) {
                  if (item.canRestore === true) {
                    actions.append(actionButton('還原檔案', 'primary wide',
                      () => restoreItem(item)));
                  }
                } else {
                  const open = document.createElement('a');
                  open.textContent = '開啟';
                  open.href = `/api/file?id=${encodeURIComponent(item.id)}`;
                  open.target = '_blank';
                  open.rel = 'noopener';
                  const download = document.createElement('a');
                  download.textContent = '下載';
                  download.href = open.href;
                  download.download = item.name;
                  actions.append(open, download);
                  if (item.canRename === true) {
                    actions.append(actionButton('重新命名', '', () => renameItem(item)));
                  }
                  if (item.canFavorite === true) {
                    actions.append(actionButton(item.favorite ? '取消最愛' : '加入最愛', '',
                      () => toggleFavorite(item)));
                  }
                  if (item.canProtect === true) {
                    actions.append(actionButton(item.protected ? '取消保護' : '啟用保護', '',
                      () => toggleProtected(item)));
                  }
                  if (item.canTrash === true) {
                    actions.append(actionButton('移到垃圾桶', 'danger',
                      () => trashItem(item)));
                  }
                }
                body.append(actions);
                card.append(body);
                return card;
              };

              const itemMatchesClientFilters = (item) => {
                const query = searchNode.value.trim().toLocaleLowerCase('zh-TW');
                return (selectedKind === 'all' || item.kind === selectedKind)
                  && (!query || item.name.toLocaleLowerCase('zh-TW').includes(query));
              };

              const setEmptyState = (visibleCount) => {
                emptyNode.hidden = visibleCount !== 0;
                if (visibleCount !== 0) return;
                if (loadError) {
                  emptyTitle.textContent = '無法載入這個檢視';
                  emptyHelp.textContent = loadError;
                  return;
                }
                if (selectedView === 'duplicates') {
                  if (duplicatesDirty) {
                    emptyTitle.textContent = '檔案內容已變更';
                    emptyHelp.textContent = '請按「開始掃描重複檔」取得新的可靠結果。';
                  } else if (!duplicateScanned) {
                    emptyTitle.textContent = '尚未掃描重複檔';
                    emptyHelp.textContent = '按上方按鈕開始掃描；掃描可能需要一些時間。';
                  } else {
                    emptyTitle.textContent = '沒有找到重複檔';
                    emptyHelp.textContent = '目前掃描結果中沒有內容相同的檔案。';
                  }
                  return;
                }
                if (selectedView === 'trash') {
                  emptyTitle.textContent = '垃圾桶是空的';
                  emptyHelp.textContent = '移到垃圾桶的檔案會顯示在這裡。';
                } else {
                  emptyTitle.textContent = '沒有符合的檔案';
                  emptyHelp.textContent = '可調整檢視、類型或搜尋條件。';
                }
              };

              const renderDuplicateGroups = () => {
                filesNode.className = 'duplicate-groups';
                const sections = [];
                let visibleFiles = 0;
                let reclaimable = 0;
                duplicateGroups.forEach((group, index) => {
                  const groupFiles = Array.isArray(group.files)
                    ? group.files.filter(itemMatchesClientFilters) : [];
                  if (!groupFiles.length) return;
                  visibleFiles += groupFiles.length;
                  reclaimable += Number.isFinite(group.reclaimableBytes)
                    ? group.reclaimableBytes : 0;
                  const section = document.createElement('section');
                  section.className = 'duplicate-group';
                  const head = document.createElement('div');
                  head.className = 'duplicate-head';
                  const title = document.createElement('h2');
                  title.textContent = `重複群組 ${index + 1}`;
                  const summary = document.createElement('p');
                  summary.textContent = `${groupFiles.length} 個檔案 · 可釋放約 ${formatBytes(group.reclaimableBytes)}`;
                  head.append(title, summary);
                  const grid = document.createElement('div');
                  grid.className = 'duplicate-files';
                  grid.replaceChildren(...groupFiles.map(renderCard));
                  section.append(head, grid);
                  sections.push(section);
                });
                filesNode.replaceChildren(...sections);
                countNode.textContent = `${sections.length} 組 · ${visibleFiles} 個檔案`
                  + (sections.length ? ` · 可釋放約 ${formatBytes(reclaimable)}` : '');
                setEmptyState(visibleFiles);
              };

              const render = () => {
                if (selectedView === 'duplicates') {
                  renderDuplicateGroups();
                  return;
                }
                filesNode.className = 'files';
                const visible = items.filter(itemMatchesClientFilters);
                filesNode.replaceChildren(...visible.map(renderCard));
                countNode.textContent = `${viewLabels[selectedView]} · ${visible.length} 個檔案`;
                setEmptyState(visible.length);
              };

              const buildFilesPath = () => {
                const query = new URLSearchParams();
                query.set('view', selectedView);
                if (selectedView === 'date') query.set('date', selectedDate);
                return `/api/files?${query.toString()}`;
              };

              const updateViewControls = () => {
                document.querySelectorAll('[data-view]').forEach((node) => {
                  node.setAttribute('aria-pressed', String(node.dataset.view === selectedView));
                });
                dateTools.hidden = selectedView !== 'date';
                duplicateTools.hidden = selectedView !== 'duplicates';
              };

              const loadDuplicates = async (scan, sequence) => {
                if (duplicatesDirty && !scan) {
                  duplicateGroups = [];
                  duplicateScanned = false;
                  loadError = '';
                  render();
                  setStatus('檔案內容已變更，請重新掃描。', true);
                  return;
                }
                const data = await api(scan ? '/api/duplicates/scan' : '/api/duplicates',
                  scan ? {method: 'POST'} : {});
                if (sequence !== loadSequence) return;
                duplicateGroups = Array.isArray(data.groups) ? data.groups : [];
                duplicateScanned = data.scanned === true;
                duplicatesDirty = false;
                duplicateScan.textContent = duplicateScanned
                  ? '重新掃描重複檔' : '開始掃描重複檔';
                loadError = '';
                render();
                setStatus(scan ? '重複檔掃描完成' : '已載入重複檔快照');
              };

              const loadCurrentView = async () => {
                const sequence = ++loadSequence;
                loadError = '';
                setStatus(selectedView === 'duplicates' ? '正在讀取重複檔結果…' : '正在讀取…');
                try {
                  if (selectedView === 'duplicates') {
                    await loadDuplicates(false, sequence);
                  } else {
                    const data = await api(buildFilesPath());
                    if (sequence !== loadSequence) return;
                    items = Array.isArray(data.files) ? data.files : [];
                    render();
                    setStatus('已更新');
                  }
                } catch (error) {
                  if (sequence !== loadSequence || error.status === 401) return;
                  loadError = error.message;
                  if (selectedView === 'duplicates') {
                    duplicateGroups = [];
                    duplicateScanned = false;
                  } else {
                    items = [];
                  }
                  render();
                  setStatus(error.message, true);
                }
              };

              const selectView = async (view) => {
                if (!validViews.has(view)) return;
                selectedView = view;
                updateViewControls();
                await loadCurrentView();
              };

              const replaceItem = (updated) => {
                if (!updated || !updated.id) return;
                items = items.map((item) => item.id === updated.id ? updated : item);
                duplicateGroups = duplicateGroups.map((group) => ({
                  ...group,
                  files: Array.isArray(group.files)
                    ? group.files.map((item) => item.id === updated.id ? updated : item)
                    : []
                }));
              };

              const removeItem = (id) => {
                items = items.filter((item) => item.id !== id);
                duplicateGroups = duplicateGroups.map((group) => ({
                  ...group,
                  files: Array.isArray(group.files)
                    ? group.files.filter((item) => item.id !== id) : []
                })).filter((group) => group.files.length >= 2);
              };

              const finishMutation = (message, updated, removedId) => {
                if (updated) replaceItem(updated);
                if (removedId) removeItem(removedId);
                if (updated && selectedView === 'favorites' && !updated.favorite) removeItem(updated.id);
                if (updated && selectedView === 'protected' && !updated.protected) removeItem(updated.id);
                if (updated && selectedView === 'trash' && !updated.trashed) removeItem(updated.id);
                duplicatesDirty = true;
                if (selectedView === 'duplicates') {
                  duplicateGroups = [];
                  duplicateScanned = false;
                }
                loadError = '';
                render();
                setStatus(selectedView === 'duplicates'
                  ? `${message}；請重新掃描重複檔。` : message);
              };

              const renameItem = async (item) => {
                if (item.canRename !== true) throw new Error('此檔案不支援重新命名。');
                const next = window.prompt('新的檔名（不可包含 / 或 \\）', item.name);
                if (next === null || next.trim() === item.name) return;
                setStatus('正在重新命名…');
                const data = await postJson('/api/rename', {id: item.id, name: next.trim()});
                finishMutation('重新命名完成', data.file, null);
              };

              const toggleFavorite = async (item) => {
                if (item.canFavorite !== true) throw new Error('此檔案不支援最愛標記。');
                const value = !item.favorite;
                setStatus(value ? '正在加入最愛…' : '正在取消最愛…');
                const data = await postJson('/api/favorite', {id: item.id, value});
                finishMutation(value ? '已加入最愛' : '已取消最愛', data.file, null);
              };

              const toggleProtected = async (item) => {
                if (item.canProtect !== true) throw new Error('此檔案不支援保護標記。');
                const value = !item.protected;
                setStatus(value ? '正在啟用保護…' : '正在取消保護…');
                const data = await postJson('/api/protected', {id: item.id, value});
                finishMutation(value ? '已啟用防誤刪保護' : '已取消防誤刪保護', data.file, null);
              };

              const trashItem = async (item) => {
                if (item.canTrash !== true) throw new Error('此檔案不支援移到垃圾桶。');
                if (!window.confirm(`將「${item.name}」移到垃圾桶？`)) return;
                setStatus('正在移到垃圾桶…');
                await postJson('/api/trash', {id: item.id});
                finishMutation('已移到垃圾桶', null, item.id);
              };

              const restoreItem = async (item) => {
                if (item.canRestore !== true) throw new Error('此檔案不支援還原。');
                if (!window.confirm(`從垃圾桶還原「${item.name}」？`)) return;
                setStatus('正在還原…');
                const data = await postJson('/api/restore', {id: item.id});
                finishMutation('檔案已還原', data.file, item.id);
              };

              const scanDuplicates = async () => {
                const sequence = ++loadSequence;
                duplicateScan.disabled = true;
                duplicateScan.textContent = '掃描中…';
                loadError = '';
                setStatus('正在掃描內容相同的檔案，請稍候…');
                try {
                  await loadDuplicates(true, sequence);
                } catch (error) {
                  if (sequence !== loadSequence || error.status === 401) return;
                  duplicateGroups = [];
                  duplicateScanned = false;
                  loadError = error.message;
                  render();
                  setStatus(error.message, true);
                } finally {
                  duplicateScan.disabled = false;
                  duplicateScan.textContent = '重新掃描重複檔';
                }
              };

              const uploadOne = (file, number, total) => new Promise((resolve, reject) => {
                const xhr = new XMLHttpRequest();
                xhr.open('POST', `/api/upload?name=${encodeURIComponent(file.name)}`);
                xhr.responseType = 'json';
                xhr.setRequestHeader('Content-Type', file.type || 'application/octet-stream');
                xhr.setRequestHeader('X-Rokid-CSRF', csrf);
                xhr.upload.addEventListener('progress', (event) => {
                  if (!event.lengthComputable) return;
                  byId('upload-progress').value = Math.round(event.loaded / event.total * 100);
                  byId('upload-label').textContent = `上傳 ${number}/${total}：${file.name}`;
                });
                xhr.addEventListener('load', () => {
                  if (xhr.status >= 200 && xhr.status < 300) {
                    resolve();
                    return;
                  }
                  if (xhr.status === 401) showPair('連線憑證已失效，請重新輸入 PIN。');
                  const error = new Error((xhr.response && xhr.response.error)
                    || `上傳失敗（${xhr.status}）`);
                  error.status = xhr.status;
                  reject(error);
                });
                xhr.addEventListener('error', () => reject(new Error('連線中斷，上傳未完成。')));
                xhr.send(file);
              });

              byId('upload').addEventListener('change', async (event) => {
                const selected = Array.from(event.target.files || []);
                if (!selected.length) return;
                const state = byId('upload-state');
                state.hidden = false;
                try {
                  for (let index = 0; index < selected.length; index += 1) {
                    await uploadOne(selected[index], index + 1, selected.length);
                  }
                  duplicatesDirty = true;
                  await selectView('all');
                  setStatus('上傳完成');
                } catch (error) {
                  if (error.status !== 401) setStatus(error.message, true);
                } finally {
                  state.hidden = true;
                  event.target.value = '';
                }
              });

              dateTools.addEventListener('submit', async (event) => {
                event.preventDefault();
                const value = dateInput.value;
                if (!/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(value)) {
                  setStatus('請選擇有效日期。', true);
                  return;
                }
                selectedDate = value;
                await selectView('date');
              });

              duplicateScan.addEventListener('click', scanDuplicates);
              searchNode.addEventListener('input', render);
              byId('refresh').addEventListener('click', loadCurrentView);

              document.querySelectorAll('[data-view]').forEach((node) => {
                node.addEventListener('click', async () => {
                  const view = node.dataset.view;
                  if (view === 'date') {
                    dateTools.hidden = false;
                    duplicateTools.hidden = true;
                    dateInput.focus();
                    return;
                  }
                  await selectView(view);
                });
              });

              document.querySelectorAll('[data-kind]').forEach((node) => {
                node.addEventListener('click', () => {
                  selectedKind = node.dataset.kind;
                  document.querySelectorAll('[data-kind]').forEach((other) =>
                    other.setAttribute('aria-pressed', String(other === node)));
                  render();
                });
              });

              document.querySelectorAll('[data-remote]').forEach((node) => {
                node.addEventListener('click', async () => {
                  const command = node.dataset.remote;
                  node.disabled = true;
                  try {
                    await api(`/api/remote?action=${encodeURIComponent(command)}`, {method: 'POST'});
                    setStatus(`已送出「${node.textContent.trim()}」`);
                  } catch (error) {
                    if (error.status !== 401) setStatus(error.message, true);
                  } finally {
                    node.disabled = false;
                  }
                });
              });

              const today = localDateValue(new Date());
              dateInput.value = today;
              dateInput.max = today;
              updateViewControls();
            })();
            """);

    private WebAssets() {
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
