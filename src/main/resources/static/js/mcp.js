/* MCP 悬浮窗：可拖动的 AI 小助手
 * - 「对话」Tab：与 AI 自由聊天（流式）。
 * - 「发消息」Tab：让 AI 帮忙群发消息（指定账号/用户名/UID/关键词，支持一句话解析、多人发送）。
 * 仅当管理端启用 message_sender 功能时，才显示「发消息」Tab。
 */
window.McpAssistant = (function () {
    var initialized = false;
    var state = {
        open: false,
        minimized: false,
        dragging: false,
        functions: [],
        senderEnabled: false,
        recipients: [],       // [{type, value}]
        streaming: false
    };

    var el = {}; // DOM 引用

    function init() {
        if (initialized) return;
        initialized = true;

        // 启动器
        var launcher = document.createElement('div');
        launcher.id = 'mcp-launcher';
        launcher.innerHTML = '<span class="mcp-launcher-ico">💬</span>';
        launcher.title = 'AI 小助手';
        launcher.addEventListener('click', toggleWindow);
        document.body.appendChild(launcher);
        el.launcher = launcher;

        // 悬浮窗
        var win = document.createElement('div');
        win.id = 'mcp-window';
        win.className = 'mcp-hidden';
        win.innerHTML =
            '<div class="mcp-header" id="mcp-header">' +
            '  <span class="mcp-title">🤖 AI 小助手</span>' +
            '  <span class="mcp-win-actions">' +
            '    <button class="mcp-btn-min" id="mcp-min" title="最小化">—</button>' +
            '    <button class="mcp-btn-close" id="mcp-close" title="关闭">×</button>' +
            '  </span>' +
            '</div>' +
            '<div class="mcp-tabs">' +
            '  <button class="mcp-tab active" data-tab="chat">对话</button>' +
            '  <button class="mcp-tab" data-tab="sender" id="mcp-tab-sender">发消息</button>' +
            '</div>' +
            '<div class="mcp-body">' +
            '  <div class="mcp-panel active" data-panel="chat">' +
            '    <div class="mcp-messages" id="mcp-messages"></div>' +
            '    <div class="mcp-input-row">' +
            '      <textarea id="mcp-chat-input" placeholder="和 AI 小助手聊点什么…（Enter 发送，Shift+Enter 换行）"></textarea>' +
            '      <button class="mcp-send" id="mcp-chat-send">发送</button>' +
            '    </div>' +
            '  </div>' +
            '  <div class="mcp-panel" data-panel="sender">' +
            '    <div class="mcp-sender-hint" id="mcp-sender-hint" style="display:none">管理员未启用「消息群发」功能，无法使用。</div>' +
            '    <label class="mcp-label">一句话描述（可空，留空则手动添加收件人）</label>' +
            '    <textarea id="mcp-instruction" placeholder="例如：给 6530823031 和小明 发消息：周末一起爬山～"></textarea>' +
            '    <button class="mcp-ai-parse" id="mcp-ai-parse">✨ 让 AI 解析收件人与内容</button>' +
            '    <label class="mcp-label">收件人（账号 / 用户名 / UID / 关键词）</label>' +
            '    <div class="mcp-recipient-add">' +
            '      <select id="mcp-recipient-type">' +
            '        <option value="ACCOUNT">账号</option>' +
            '        <option value="USERNAME">用户名</option>' +
            '        <option value="UID">UID</option>' +
            '        <option value="KEYWORD">关键词</option>' +
            '      </select>' +
            '      <input id="mcp-recipient-value" placeholder="如 6530823031 / 小明 / 12 / 张" />' +
            '      <button id="mcp-recipient-add-btn">添加</button>' +
            '    </div>' +
            '    <div class="mcp-chips" id="mcp-chips"></div>' +
            '    <label class="mcp-label">消息内容</label>' +
            '    <textarea id="mcp-content" placeholder="要发送的消息正文"></textarea>' +
            '    <div class="mcp-preview" id="mcp-preview"></div>' +
            '    <div class="mcp-sender-actions">' +
            '      <button class="mcp-preview-btn" id="mcp-preview-btn">预览</button>' +
            '      <button class="mcp-submit" id="mcp-send-btn">发送</button>' +
            '    </div>' +
            '  </div>' +
            '</div>';
        document.body.appendChild(win);
        el.win = win;

        // 引用子元素
        el.header = win.querySelector('#mcp-header');
        el.tabSender = win.querySelector('#mcp-tab-sender');
        el.messages = win.querySelector('#mcp-messages');
        el.chatInput = win.querySelector('#mcp-chat-input');
        el.chips = win.querySelector('#mcp-chips');
        el.preview = win.querySelector('#mcp-preview');
        el.senderHint = win.querySelector('#mcp-sender-hint');

        bindEvents();
        loadFunctions();
        appendSystemBubble('你好，我是 moyo AI 小助手。我可以陪你聊天，也能帮你给好友群发消息～');
    }

    function bindEvents() {
        el.win.querySelector('#mcp-close').addEventListener('click', function (e) {
            e.stopPropagation(); closeWindow();
        });
        el.win.querySelector('#mcp-min').addEventListener('click', function (e) {
            e.stopPropagation(); minimize();
        });
        // tab 切换
        var tabs = el.win.querySelectorAll('.mcp-tab');
        tabs.forEach(function (t) {
            t.addEventListener('click', function () { switchTab(t.getAttribute('data-tab')); });
        });
        // 对话发送
        el.win.querySelector('#mcp-chat-send').addEventListener('click', sendChat);
        el.chatInput.addEventListener('keydown', function (e) {
            if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendChat(); }
        });
        // 发消息
        el.win.querySelector('#mcp-ai-parse').addEventListener('click', aiParse);
        el.win.querySelector('#mcp-recipient-add-btn').addEventListener('click', addRecipientFromInput);
        el.win.querySelector('#mcp-preview-btn').addEventListener('click', doPreview);
        el.win.querySelector('#mcp-send-btn').addEventListener('click', doSend);
        // 拖拽
        enableDrag();
    }

    /* ===== 功能列表（决定「发消息」Tab 是否可见） ===== */
    function loadFunctions() {
        if (!window.Api || !App || !App.token) return;
        window.Api.mcpFunctions().then(function (r) {
            if (r && r.code === 0 && Array.isArray(r.data)) {
                state.functions = r.data;
                state.senderEnabled = r.data.some(function (f) { return f.code === 'message_sender'; });
                if (state.senderEnabled) {
                    el.tabSender.style.display = '';
                } else {
                    el.tabSender.style.display = 'none';
                    el.senderHint.style.display = 'block';
                    // 切回对话
                    switchTab('chat');
                }
            }
        });
    }

    /* ===== 窗口显隐 ===== */
    function toggleWindow() {
        if (state.open && !state.minimized) { minimize(); }
        else { openWindow(); }
    }
    function openWindow() {
        state.open = true; state.minimized = false;
        el.win.classList.remove('mcp-hidden');
        el.win.classList.remove('mcp-minimized');
        el.launcher.classList.add('mcp-launcher-active');
        // 重新拉一次功能开关（可能登录态变化）
        loadFunctions();
    }
    function closeWindow() {
        state.open = false;
        el.win.classList.add('mcp-hidden');
        el.launcher.classList.remove('mcp-launcher-active');
    }
    function minimize() {
        state.minimized = true;
        el.win.classList.add('mcp-minimized');
        el.launcher.classList.remove('mcp-launcher-active');
    }

    function switchTab(tab) {
        var tabs = el.win.querySelectorAll('.mcp-tab');
        tabs.forEach(function (t) { t.classList.toggle('active', t.getAttribute('data-tab') === tab); });
        var panels = el.win.querySelectorAll('.mcp-panel');
        panels.forEach(function (p) { p.classList.toggle('active', p.getAttribute('data-panel') === tab); });
    }

    /* ===== 对话（流式） ===== */
    function sendChat() {
        if (state.streaming) return;
        var text = el.chatInput.value.trim();
        if (!text) return;
        el.chatInput.value = '';
        appendUserBubble(text);
        var bubble = appendAssistantBubble('');
        state.streaming = true;
        if (!window.Api || !App || !App.token) {
            bubble.textContent = '（请先登录后再使用）';
            state.streaming = false;
            return;
        }
        window.Api.aiStream({ mode: 'chat', prompt: text },
            function (tok) { bubble.textContent += tok; el.messages.scrollTop = el.messages.scrollHeight; },
            function () { state.streaming = false; },
            function (err) { bubble.textContent += '\n[出错了：' + err + ']'; state.streaming = false; }
        );
    }

    function appendSystemBubble(text) {
        var d = document.createElement('div');
        d.className = 'mcp-msg mcp-sys';
        d.textContent = text;
        el.messages.appendChild(d);
        el.messages.scrollTop = el.messages.scrollHeight;
    }
    function appendUserBubble(text) {
        var d = document.createElement('div');
        d.className = 'mcp-msg mcp-me';
        d.textContent = text;
        el.messages.appendChild(d);
        el.messages.scrollTop = el.messages.scrollHeight;
    }
    function appendAssistantBubble(text) {
        var d = document.createElement('div');
        d.className = 'mcp-msg mcp-ai';
        d.textContent = text;
        el.messages.appendChild(d);
        el.messages.scrollTop = el.messages.scrollHeight;
        return d;
    }

    /* ===== 发消息 ===== */
    function addRecipientFromInput() {
        var type = el.win.querySelector('#mcp-recipient-type').value;
        var val = el.win.querySelector('#mcp-recipient-value').value.trim();
        if (!val) return;
        state.recipients.push({ type: type, value: val });
        el.win.querySelector('#mcp-recipient-value').value = '';
        renderChips();
    }
    function removeRecipient(idx) {
        state.recipients.splice(idx, 1);
        renderChips();
    }
    function renderChips() {
        el.chips.innerHTML = '';
        if (state.recipients.length === 0) {
            var empty = document.createElement('span');
            empty.className = 'mcp-chip-empty';
            empty.textContent = '暂无收件人';
            el.chips.appendChild(empty);
            return;
        }
        state.recipients.forEach(function (r, i) {
            var c = document.createElement('span');
            c.className = 'mcp-chip';
            c.innerHTML = '<span class="mcp-chip-type">' + typeLabel(r.type) + '</span>' +
                '<span class="mcp-chip-val"></span>' +
                '<button class="mcp-chip-x" title="移除">×</button>';
            c.querySelector('.mcp-chip-val').textContent = r.value;
            c.querySelector('.mcp-chip-x').addEventListener('click', function () { removeRecipient(i); });
            el.chips.appendChild(c);
        });
    }
    function typeLabel(t) {
        return ({ ACCOUNT: '账号', USERNAME: '用户名', UID: 'UID', KEYWORD: '关键词' })[t] || t;
    }

    function aiParse() {
        if (!state.senderEnabled) { App && App.toast && App.toast('消息群发功能未启用'); return; }
        var instruction = el.win.querySelector('#mcp-instruction').value.trim();
        if (!instruction) { App && App.toast && App.toast('请先输入一句话描述'); return; }
        el.preview.innerHTML = '<div class="mcp-loading">AI 解析中…</div>';
        window.Api.mcpPreview({ instruction: instruction }).then(function (r) {
            if (r && r.code === 0 && r.data) {
                var d = r.data;
                if (d.recipients && d.recipients.length) {
                    state.recipients = d.recipients.map(function (x) {
                        return { type: x.type, value: x.value };
                    });
                    renderChips();
                }
                if (d.content) el.win.querySelector('#mcp-content').value = d.content;
                renderPreview(d);
                App && App.toast && App.toast('已解析出 ' + (d.resolvedCount || 0) + ' 位收件人');
            } else {
                el.preview.innerHTML = '<div class="mcp-err">' + ((r && r.message) || '解析失败') + '</div>';
            }
        }).catch(function (e) {
            el.preview.innerHTML = '<div class="mcp-err">解析失败：' + e + '</div>';
        });
    }

    function doPreview() {
        if (!state.senderEnabled) { App && App.toast && App.toast('消息群发功能未启用'); return; }
        var content = el.win.querySelector('#mcp-content').value.trim();
        if (!state.recipients.length) { App && App.toast && App.toast('请添加收件人'); return; }
        if (!content) { App && App.toast && App.toast('请填写消息内容'); return; }
        el.preview.innerHTML = '<div class="mcp-loading">校验收件人中…</div>';
        window.Api.mcpPreview({ recipients: state.recipients, content: content }).then(function (r) {
            if (r && r.code === 0 && r.data) renderPreview(r.data);
            else el.preview.innerHTML = '<div class="mcp-err">' + ((r && r.message) || '预览失败') + '</div>';
        }).catch(function (e) {
            el.preview.innerHTML = '<div class="mcp-err">预览失败：' + e + '</div>';
        });
    }

    function renderPreview(d) {
        var list = d.recipients || [];
        var html = '<div class="mcp-preview-head">将发送给 ' + (d.resolvedCount || 0) + ' 位已匹配收件人：</div>';
        html += '<ul class="mcp-preview-list">';
        list.forEach(function (r) {
            if (r.resolved) {
                html += '<li class="ok">✅ ' + escapeHtml((r.nickname || r.username || '')) +
                    ' <span class="mcp-sub">(' + escapeHtml(r.account || '') + ')</span></li>';
            } else {
                html += '<li class="bad">⚠️ ' + typeLabel(r.type) + '「' + escapeHtml(r.value) + '」' +
                    ' <span class="mcp-sub">' + escapeHtml(r.reason || '未匹配') + '</span></li>';
            }
        });
        html += '</ul>';
        el.preview.innerHTML = html;
    }

    function doSend() {
        if (!state.senderEnabled) { App && App.toast && App.toast('消息群发功能未启用'); return; }
        var content = el.win.querySelector('#mcp-content').value.trim();
        if (!state.recipients.length) { App && App.toast && App.toast('请添加收件人'); return; }
        if (!content) { App && App.toast && App.toast('请填写消息内容'); return; }
        var btn = el.win.querySelector('#mcp-send-btn');
        btn.disabled = true; btn.textContent = '发送中…';
        window.Api.mcpSend({ recipients: state.recipients, content: content }).then(function (r) {
            btn.disabled = false; btn.textContent = '发送';
            if (r && r.code === 0 && r.data) {
                var d = r.data;
                var msg = '已发送给 ' + (d.successCount || 0) + ' 人';
                if (d.failed && d.failed.length) msg += '，' + d.failed.length + ' 人失败';
                if (d.skipped && d.skipped.length) msg += '，跳过 ' + d.skipped.length + ' 人';
                App && App.toast && App.toast(msg);
                var detail = '<div class="mcp-send-result">' + escapeHtml(msg) + '</div>';
                if (d.failed && d.failed.length) {
                    detail += '<ul class="mcp-preview-list">';
                    d.failed.forEach(function (f) {
                        detail += '<li class="bad">⚠️ ' + typeLabel(f.type) + '「' + escapeHtml(String(f.value)) + '」' +
                            ' <span class="mcp-sub">' + escapeHtml(f.reason || '') + '</span></li>';
                    });
                    detail += '</ul>';
                }
                el.preview.innerHTML = detail;
            } else {
                el.preview.innerHTML = '<div class="mcp-err">' + ((r && r.message) || '发送失败') + '</div>';
            }
        }).catch(function (e) {
            btn.disabled = false; btn.textContent = '发送';
            el.preview.innerHTML = '<div class="mcp-err">发送失败：' + e + '</div>';
        });
    }

    /* ===== 拖拽（支持鼠标 + 触摸，用 pointer 事件） ===== */
    function enableDrag() {
        var startX, startY, origX, origY;
        el.header.addEventListener('pointerdown', function (e) {
            if (e.target.closest('button')) return; // 不拦截按钮
            state.dragging = true;
            var rect = el.win.getBoundingClientRect();
            origX = rect.left; origY = rect.top;
            startX = e.clientX; startY = e.clientY;
            el.win.style.right = 'auto';
            el.win.style.bottom = 'auto';
            el.win.style.left = origX + 'px';
            el.win.style.top = origY + 'px';
            el.header.setPointerCapture(e.pointerId);
        });
        el.header.addEventListener('pointermove', function (e) {
            if (!state.dragging) return;
            var dx = e.clientX - startX, dy = e.clientY - startY;
            var nx = Math.max(0, Math.min(window.innerWidth - 60, origX + dx));
            var ny = Math.max(0, Math.min(window.innerHeight - 40, origY + dy));
            el.win.style.left = nx + 'px';
            el.win.style.top = ny + 'px';
        });
        el.header.addEventListener('pointerup', function (e) {
            state.dragging = false;
            try { el.header.releasePointerCapture(e.pointerId); } catch (err) {}
        });
    }

    function escapeHtml(s) {
        if (s == null) return '';
        return String(s).replace(/[&<>"']/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
        });
    }

    return {
        init: init,
        open: openWindow
    };
})();

// 登录完成后初始化悬浮窗（App 登录流程会触发；这里再兜底一次）
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () { window.McpAssistant && window.McpAssistant.init(); });
} else {
    window.McpAssistant && window.McpAssistant.init();
}
// App 登录成功后也确保初始化
if (window.App && typeof App.onLogin === 'function') {
    var _orig = App.onLogin;
    App.onLogin = function () { try { _orig && _orig.apply(App, arguments); } finally { window.McpAssistant && window.McpAssistant.init(); } };
}
