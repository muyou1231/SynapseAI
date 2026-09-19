/* 独立管理后台逻辑（admin.html 专用）。与普通用户端完全解耦，仅本文件 + admin.html 使用。 */
window.AdminApp = (function () {
    var TOKEN_KEY = 'adminToken';
    var NAME_KEY = 'adminName';
    var token = localStorage.getItem(TOKEN_KEY);

    function el(id) { return document.getElementById(id); }

    /* 管理端自制确认弹窗（替代 App.confirm：admin 页面不加载 app.js，故 App 不存在）。
     * 返回 Promise<boolean>：点遮罩 / 按 Esc = 取消，回车 / 确定 = true。复用 admin 弹窗样式体系。 */
    function confirmModal(message, opts) {
        opts = opts || {};
        var title = opts.title || '请确认';
        var okText = opts.okText || '确定';
        var cancelText = opts.cancelText || '取消';
        var danger = !!opts.danger;
        return new Promise(function (resolve) {
            var overlay = document.createElement('div');
            overlay.className = 'ad-modal';
            overlay.innerHTML =
                '<div class="ad-modal-card">' +
                '<div class="ad-modal-title">' + esc(title) + '</div>' +
                '<div class="ad-modal-sub">' + esc(message) + '</div>' +
                '<div class="ad-modal-actions">' +
                '<button type="button" class="ad-btn ghost" id="cf-cancel">' + esc(cancelText) + '</button>' +
                '<button type="button" class="ad-btn ' + (danger ? 'danger' : 'ok') + '" id="cf-ok">' + esc(okText) + '</button>' +
                '</div></div>';
            document.body.appendChild(overlay);
            var closed = false;
            function close(v) {
                if (closed) return;
                closed = true;
                overlay.remove();
                document.removeEventListener('keydown', onKey);
                resolve(v);
            }
            overlay.querySelector('#cf-cancel').onclick = function () { close(false); };
            overlay.querySelector('#cf-ok').onclick = function () { close(true); };
            overlay.addEventListener('click', function (e) { if (e.target === overlay) close(false); });
            function onKey(e) {
                if (e.key === 'Escape') close(false);
                else if (e.key === 'Enter') close(true);
            }
            document.addEventListener('keydown', onKey);
        });
    }

    function showLogin() {
        el('ad-login').classList.remove('hidden');
        el('ad-shell').classList.add('hidden');
    }
    function showShell() {
        el('ad-login').classList.add('hidden');
        el('ad-shell').classList.remove('hidden');
        var n = localStorage.getItem(NAME_KEY);
        if (n) el('ad-admin-name').textContent = '管理员：' + n;
    }

    function setLoginMsg(text, err) {
        var e = el('ad-login-msg');
        if (!e) return;
        e.textContent = text || '';
        e.className = 'ad-login-msg' + (err ? ' err' : '');
    }

    /* 封装管理端接口调用，自动带 X-Token；401 视为过期，退回登录 */
    function api(method, path, body) {
        var opt = { method: method, headers: { 'Content-Type': 'application/json' } };
        if (token) opt.headers['X-Token'] = token;
        if (body !== undefined) opt.body = JSON.stringify(body);
        return fetch(path, opt).then(function (r) {
            if (r.status === 401) { logout(); throw new Error('登录已过期，请重新登录'); }
            return r.text().then(function (t) {
                try { return JSON.parse(t); } catch (e) { return { code: 1, message: t }; }
            });
        });
    }

    /** 文件上传（与 api.js 的 Api.upload 等价）：走管理端自有 token，不依赖全局 Api / App。
     *  用 FormData 让浏览器自动设置 multipart boundary；返回 { code, data:{url,...} }。 */
    function uploadFile(file, prefix) {
        var fd = new FormData();
        fd.append('file', file);
        if (prefix) fd.append('prefix', prefix);
        var opt = { method: 'POST', headers: {} };
        if (token) opt.headers['X-Token'] = token;
        opt.body = fd;
        return fetch('/api/file/upload', opt).then(function (r) {
            if (r.status === 401) { logout(); throw new Error('登录已过期，请重新登录'); }
            return r.text().then(function (t) {
                try { return JSON.parse(t); } catch (e) { return { code: 1, message: t }; }
            });
        });
    }

    function login() {
        var uname = el('ad-username').value.trim();
        var pwd = el('ad-password').value;
        if (!uname || !pwd) { setLoginMsg('请输入账号和密码', true); return; }
        setLoginMsg('登录中...', false);
        fetch('/api/user/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ account: uname, password: pwd })
        }).then(function (r) { return r.json(); }).then(function (d) {
            if (!d || d.code !== 0 || !d.data || !d.data.token) {
                setLoginMsg(d && d.message ? d.message : '登录失败', true);
                return;
            }
            token = d.data.token;
            localStorage.setItem(TOKEN_KEY, token);
            if (d.data.user && d.data.user.nickname) {
                localStorage.setItem(NAME_KEY, d.data.user.nickname);
            }
            return api('GET', '/api/admin/check').then(function (c) {
                if (c && c.code === 0 && c.data === true) {
                    showShell();
                    showView('overview');
                } else {
                    logout();
                    setLoginMsg('该账号不是管理员，无权限进入', true);
                }
            });
        }).catch(function (e) {
            setLoginMsg(e.message || '登录失败', true);
        });
    }

    function logout() {
        token = null;
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(NAME_KEY);
        showLogin();
    }

    function showView(name) {
        document.querySelectorAll('.ad-nav-item').forEach(function (b) {
            b.classList.toggle('active', b.getAttribute('data-view') === name);
        });
        if (name === 'overview') renderOverview();
        else if (name === 'users') renderUsers();
        else if (name === 'messages') renderMessages();
        else         if (name === 'moments') renderMoments();
        else if (name === 'review') renderReview();
        else if (name === 'assistant') renderAssistant();
        else if (name === 'groups') renderGroups();
        else if (name === 'notices') renderNotices();
        else if (name === 'mcp') renderMcp();
    }

    function esc(s) {
        if (s == null) return '';
        return String(s).replace(/[&<>"']/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
        });
    }

    function fmtTime(t) {
        if (!t) return '';
        return String(t).replace('T', ' ').substring(0, 19);
    }

    function renderOverview() {
        api('GET', '/api/admin/stats').then(function (d) {
            if (!d || d.code !== 0) { el('ad-main').innerHTML = '<div class="ad-empty">加载失败</div>'; return; }
            var s = d.data || {};
            var total = s.userTotal || 0;
            var online = s.onlineCount || 0;
            var frozen = s.frozenCount || 0;
            var normal = Math.max(0, total - online - frozen);
            var pOn = total ? (online / total * 100) : 0;
            var pFr = total ? (frozen / total * 100) : 0;
            var donut = 'conic-gradient(#07c160 0 ' + pOn + '%, #ff4d4f ' + pOn + '% ' + (pOn + pFr) + '%, #fa8c16 ' + (pOn + pFr) + '% 100%)';
            var cards = [
                ['用户总数', s.userTotal, '#13c2c2'], ['在线用户', s.onlineCount, '#07c160'],
                ['已冻结', s.frozenCount, '#ff4d4f'], ['消息总数', s.messageTotal, '#1890ff'],
                ['群聊数', s.groupTotal, '#722ed1'], ['朋友圈动态', s.momentTotal, '#eb2f96'],
                ['好友关系', s.friendshipTotal, '#fa8c16']
            ];
            var statHtml = '<div class="ad-cards">';
            cards.forEach(function (c) {
                statHtml += '<div class="ad-card" style="border-left:4px solid ' + c[2] + '">' +
                    '<div class="ad-card-num" style="color:' + c[2] + '">' + esc(c[1]) + '</div>' +
                    '<div class="ad-card-label">' + esc(c[0]) + '</div></div>';
            });
            statHtml += '</div>';

            var bars = [
                ['消息总数', s.messageTotal, '#1890ff'], ['朋友圈动态', s.momentTotal, '#eb2f96'],
                ['群聊数', s.groupTotal, '#722ed1'], ['好友关系', s.friendshipTotal, '#fa8c16'],
                ['用户总数', s.userTotal, '#13c2c2']
            ];
            var max = 1;
            bars.forEach(function (b) { if ((b[1] || 0) > max) max = b[1]; });
            var barHtml = bars.map(function (b) {
                var w = max ? Math.round((b[1] || 0) / max * 100) : 0;
                return '<div class="ad-bar-row"><div class="ad-bar-label">' + esc(b[0]) + '</div>' +
                    '<div class="ad-bar-track"><div class="ad-bar-fill" style="width:' + w + '%;background:' + b[2] + '"></div></div>' +
                    '<div class="ad-bar-val">' + esc(b[1]) + '</div></div>';
            }).join('');

            var html = '<div class="ad-section-title">系统概览</div>' + statHtml +
                '<div class="ad-charts">' +
                '<div class="ad-chart-box"><div class="ad-chart-title">用户状态分布</div>' +
                '<div class="ad-donut-wrap"><div class="ad-donut" style="background:' + donut + '">' +
                '<div class="ad-donut-hole"><div class="ad-donut-num">' + total + '</div><div class="ad-donut-sub">用户总数</div></div></div>' +
                '<div class="ad-legend">' +
                '<div class="ad-legend-item"><span class="ad-dot" style="background:#07c160"></span>在线 ' + online + '</div>' +
                '<div class="ad-legend-item"><span class="ad-dot" style="background:#ff4d4f"></span>已冻结 ' + frozen + '</div>' +
                '<div class="ad-legend-item"><span class="ad-dot" style="background:#fa8c16"></span>正常离线 ' + normal + '</div>' +
                '</div></div></div>' +
                '<div class="ad-chart-box"><div class="ad-chart-title">数据量分布</div>' + barHtml + '</div>' +
                '</div>' +
                '<div class="ad-hint">最高权限后台：可查看全部用户、冻结/解冻账号、审计任意聊天内容、查看朋友圈与群聊、打回违规动态。</div>';
            el('ad-main').innerHTML = html;
        });
    }

    function renderUsers() {
        var html = '<div class="ad-section-title">用户管理</div>' +
            '<div class="ad-toolbar">' +
            '<input id="ad-user-kw" class="ad-input" placeholder="搜索账号/用户名/昵称" onkeydown="if(event.key===\'Enter\')AdminApp.loadUsers()">' +
            '<button class="ad-btn" onclick="AdminApp.loadUsers()">搜索</button>' +
            '<button class="ad-btn ghost" onclick="AdminApp.loadUsers()">刷新</button>' +
            '</div>' +
            '<div class="ad-table-wrap"><table class="ad-table" id="ad-user-table"><thead><tr>' +
            '<th>头像</th><th>ID</th><th>账号</th><th>用户名</th><th>昵称</th><th>角色</th><th>在线</th><th>状态</th><th>注册时间</th><th>操作</th>' +
            '</tr></thead><tbody></tbody></table></div>';
        el('ad-main').innerHTML = html;
        loadUsers();
    }

    function loadUsers() {
        var kw = el('ad-user-kw') ? el('ad-user-kw').value.trim() : '';
        api('GET', '/api/admin/users' + (kw ? '?kw=' + encodeURIComponent(kw) : '')).then(function (d) {
            var tbody = document.querySelector('#ad-user-table tbody');
            if (!d || d.code !== 0 || !Array.isArray(d.data)) { tbody.innerHTML = '<tr><td colspan="9">加载失败</td></tr>'; return; }
            if (d.data.length === 0) { tbody.innerHTML = '<tr><td colspan="9">无用户</td></tr>'; return; }
            tbody.innerHTML = d.data.map(function (u) {
                var frozen = u.frozen;
                var op = frozen
                    ? '<button class="ad-btn" onclick="AdminApp.setFrozen(' + u.id + ', false)">解冻</button>'
                    : '<button class="ad-btn danger" onclick="AdminApp.openFreeze(' + u.id + ')">冻结</button>';
                // 强制下线：仅在线且非当前管理员自己时可用
                if (u.online && !u.frozen) {
                    op += ' <button class="ad-btn warn" onclick="AdminApp.offline(' + u.id + ')">强制下线</button>';
                }
                return '<tr>' +
                    '<td>' + avatarHtml(u) + '</td>' +
                    '<td>' + esc(u.id) + '</td>' +
                    '<td>' + esc(u.account) + '</td>' +
                    '<td>' + esc(u.username) + '</td>' +
                    '<td>' + esc(u.nickname) + '</td>' +
                    '<td>' + esc(u.role) + '</td>' +
                    '<td>' + (u.online ? '在线' : '离线') + '</td>' +
                    '<td>' + (frozen ? '<span class="ad-badge danger">已冻结</span>' : '<span class="ad-badge ok">正常</span>') + '</td>' +
                    '<td>' + esc(fmtTime(u.createTime)) + '</td>' +
                    '<td>' + op + '</td>' +
                    '</tr>';
            }).join('');
        });
    }

    async function offline(id) {
        if (!await confirmModal('确定强制下线该用户吗？其当前登录会话将被立即断开。', { danger: true })) return;
        api('POST', '/api/admin/users/' + id + '/offline').then(function (d) {
            if (d && d.code === 0) { loadUsers(); }
            else { alert(d && d.message ? d.message : '操作失败'); }
        });
    }

    var _freezeId = null;
    function openFreeze(id) {
        _freezeId = id;
        var ta = el('ad-freeze-reason');
        if (ta) ta.value = '';
        el('ad-freeze-modal').classList.remove('hidden');
    }
    function cancelFreeze() {
        _freezeId = null;
        el('ad-freeze-modal').classList.add('hidden');
    }
    function confirmFreeze() {
        if (_freezeId == null) return;
        var reason = el('ad-freeze-reason') ? el('ad-freeze-reason').value.trim() : '';
        api('POST', '/api/admin/users/' + _freezeId + '/freeze', { reason: reason }).then(function (d) {
            cancelFreeze();
            if (d && d.code === 0) { loadUsers(); }
            else { alert(d && d.message ? d.message : '操作失败'); }
        }).catch(function () { cancelFreeze(); });
    }

    function setFrozen(id, frozen) {
        api('POST', '/api/admin/users/' + id + (frozen ? '/freeze' : '/unfreeze')).then(function (d) {
            if (d && d.code === 0) { loadUsers(); }
            else { alert(d && d.message ? d.message : '操作失败'); }
        });
    }

    function renderMessages() {
        var html = '<div class="ad-section-title">聊天记录审计</div>' +
            '<div class="ad-toolbar ad-msg-bar">' +
            '<label><input type="radio" name="ad-msg-type" value="USER" checked onchange="AdminApp.toggleMsgType();AdminApp.onMsgChange()"> 用户私聊</label>' +
            '<label><input type="radio" name="ad-msg-type" value="GROUP" onchange="AdminApp.toggleMsgType();AdminApp.onMsgChange()"> 群聊</label>' +
            '<select id="ad-msg-userA" class="ad-input" onchange="AdminApp.onMsgChange()"></select>' +
            '<select id="ad-msg-userB" class="ad-input" onchange="AdminApp.onMsgChange()"><option value="">（选择对方）</option></select>' +
            '<select id="ad-msg-viewas" class="ad-input" title="以谁的视角查看（头像显示在右侧）" onchange="AdminApp.loadMessages()"></select>' +
            '<select id="ad-msg-group" class="ad-input hidden"><option value="">选择群</option></select>' +
            '<input id="ad-msg-kw" class="ad-input" placeholder="关键字（可选，输入后点查询筛选）">' +
            '<button class="ad-btn" onclick="AdminApp.loadMessages()">查询</button>' +
            '</div>' +
            '<div id="ad-msg-hint" class="ad-hint">请选择「查看对象」与「对方」，系统将自动加载两人之间的聊天记录（最新消息在底部）；输入关键字后点「查询」可在当前会话内筛选。</div>' +
            '<div id="ad-msg-list" class="ad-msg-list"></div>';
        el('ad-main').innerHTML = html;
        api('GET', '/api/admin/users').then(function (d) {
            if (d && d.code === 0 && Array.isArray(d.data)) {
                var opts = d.data.map(function (u) {
                    return '<option value="' + u.id + '">' + esc(u.nickname || u.username) + '(#' + esc(u.account) + ')</option>';
                }).join('');
                el('ad-msg-userA').innerHTML = '<option value="">（选择查看对象）</option>' + opts;
                el('ad-msg-userB').innerHTML = '<option value="">（选择对方）</option>' + opts;
                refreshViewAs();
            }
        });
        api('GET', '/api/admin/groups').then(function (d) {
            if (d && d.code === 0 && Array.isArray(d.data)) {
                el('ad-msg-group').innerHTML = '<option value="">选择群</option>' + d.data.map(function (g) {
                    return '<option value="' + g.id + '">' + esc(g.name) + '(#' + g.id + ')</option>';
                }).join('');
            }
        });
    }

    /* 重新生成「视角（右侧头像）」下拉：选项为用户A、用户B，默认用户A */
    function refreshViewAs() {
        var a = el('ad-msg-userA');
        var b = el('ad-msg-userB');
        var sel = el('ad-msg-viewas');
        if (!a || !sel) return;
        var opts = '';
        if (a.value) {
            var txtA = a.options[a.selectedIndex] ? a.options[a.selectedIndex].text : '用户A';
            opts += '<option value="' + a.value + '">' + esc(txtA) + '（右侧）</option>';
        }
        if (b && b.value) {
            var txtB = b.options[b.selectedIndex] ? b.options[b.selectedIndex].text : '用户B';
            opts += '<option value="' + b.value + '">' + esc(txtB) + '（右侧）</option>';
        }
        if (!a.value && !(b && b.value)) opts = '<option value="">（请先选择用户）</option>';
        sel.innerHTML = opts;
    }

    function toggleMsgType() {
        var type = document.querySelector('input[name="ad-msg-type"]:checked').value;
        el('ad-msg-userA').classList.toggle('hidden', type === 'GROUP');
        el('ad-msg-userB').classList.toggle('hidden', type === 'GROUP');
        el('ad-msg-viewas').classList.toggle('hidden', type === 'GROUP');
        el('ad-msg-group').classList.toggle('hidden', type === 'USER');
    }

    /* 选中查看对象/对方后自动搜索；未选齐则给出提示。查询按钮专用于关键字筛选 */
    function onMsgChange() {
        var type = document.querySelector('input[name="ad-msg-type"]:checked').value;
        var box = el('ad-msg-list');
        if (type === 'USER') {
            var a = el('ad-msg-userA').value;
            var b = el('ad-msg-userB').value;
            if (a && b) {
                refreshViewAs();
                loadMessages();
            } else {
                box.className = 'ad-msg-list';
                box.innerHTML = '<div class="ad-empty">请同时选择「查看对象」与「对方」，系统将自动加载两人之间的聊天记录</div>';
            }
        } else {
            var g = el('ad-msg-group').value;
            if (g) {
                loadMessages();
            } else {
                box.className = 'ad-msg-list';
                box.innerHTML = '<div class="ad-empty">请选择群聊，系统将自动加载群聊天记录</div>';
            }
        }
    }

    /* 与普通端 App.avatarHtml 保持一致：有头像返回 <img class="avatar">，无头像返回带底色的字母占位 */
    function avatarHtml(u) {
        var av = u.avatar;
        if (av) return '<img class="avatar" src="' + esc(av) + '" alt="">';
        var init = (u.nickname || '?').slice(0, 1);
        return '<div class="avatar" style="background:var(--accent,#07c160);color:#fff;display:flex;align-items:center;justify-content:center;font-weight:600">' + esc(init) + '</div>';
    }

    /* 气泡线程视图：DOM 结构与普通聊天页 bubbleNode 完全一致（.msg > .avatar + .bubble），仅无输入框 */
    function renderMsgThread(msgs, viewAsId, groupMode) {
        // 后端按时间倒序返回（最新在前），这里反转为正序：最新消息显示在底部（仿正常聊天）
        var list = (msgs || []).slice().reverse();
        var html = '<div class="ad-chat-thread">';
        var prevKey = '';
        list.forEach(function (m) {
            var timeStr = fmtTime(m.createTime);
            var key = timeStr.substring(0, 16);
            if (key !== prevKey) { html += '<div class="msg-time">' + esc(timeStr) + '</div>'; prevKey = key; }
            var isMe = !groupMode && viewAsId != null && Number(m.senderId) === Number(viewAsId);
            var cls = 'msg' + (isMe ? ' me' : '') + (m.urgent ? ' urgent' : '');
            var tag = m.urgent ? '<div class="urgent-tag">⚡ 加急</div>' : '';
            var body = m.type === 'IMAGE'
                ? '<img src="' + esc(m.content) + '" onclick="window.open(\'' + esc(m.content) + '\')">'
                : esc(m.content);
            var recall = m.recalled ? '<span class="ad-badge">已撤回</span> ' : '';
            // 群聊模式在气泡内显示发送者昵称（仅左侧消息），其余与普通端一致
            var nameLine = (groupMode && !isMe) ? '<div class="msg-sender-name">' + esc(m.senderName) + '</div>' : '';
            html += '<div class="' + cls + '" data-mid="' + esc(m.id) + '">' +
                avatarHtml({ id: m.senderId, nickname: m.senderName, avatar: m.senderAvatar }) +
                '<div class="bubble">' + tag + recall + nameLine + body + '</div>' +
                '</div>';
        });
        html += '</div>';
        html += '<div class="ad-chat-readonly">🔒 只读模式：管理员以查看视角浏览聊天记录，不可发送消息</div>';
        return html;
    }

    /* 列表视图（关键字搜索等跨会话场景） */
    function renderMsgList(msgs) {
        return '<div class="ad-msg-list">' + msgs.map(function (m) {
            var recallTag = m.recalled ? ' <span class="ad-badge">已撤回</span>' : '';
            var urgentTag = m.urgent ? ' <span class="ad-badge warn">⚡加急</span>' : '';
            var content = m.type === 'IMAGE' ? '[图片]' : esc(m.content);
            return '<div class="ad-msg-row">' +
                '<div class="ad-msg-meta"><b>' + esc(m.senderName) + '</b> → <b>' + esc(m.targetName) + '</b>' +
                ' <span class="ad-msg-time">' + esc(fmtTime(m.createTime)) + '</span>' + recallTag + urgentTag + '</div>' +
                '<div class="ad-msg-content">' + content + '</div>' +
                '</div>';
        }).join('') + '</div>';
    }

    function loadMessages() {
        var type = document.querySelector('input[name="ad-msg-type"]:checked').value;
        var kw = el('ad-msg-kw').value.trim();
        var params = '?targetType=' + type + '&page=0&size=200';
        if (type === 'USER') {
            var a = el('ad-msg-userA').value;
            var b = el('ad-msg-userB').value;
            if (!a) { alert('请选择用户A'); return; }
            params += '&userId=' + a;
            if (b) params += '&peerId=' + b;
        } else {
            var g = el('ad-msg-group').value;
            if (!g) { alert('请选择群'); return; }
            params += '&groupId=' + g;
        }
        if (kw) params += '&keyword=' + encodeURIComponent(kw);
        api('GET', '/api/admin/messages' + params).then(function (d) {
            var box = el('ad-msg-list');
            if (!d || d.code !== 0 || !Array.isArray(d.data)) { box.className = 'ad-msg-list'; box.innerHTML = '<div class="ad-empty">加载失败</div>'; return; }
            if (d.data.length === 0) { box.className = 'ad-msg-list'; box.innerHTML = '<div class="ad-empty">无消息</div>'; return; }
            // 关键字搜索跨多会话，用列表视图；其余用仿聊天气泡线程
            if (kw) {
                box.className = 'ad-msg-list';
                box.innerHTML = renderMsgList(d.data);
            } else if (type === 'GROUP') {
                box.className = 'ad-msg-list ad-msg-thread-wrap';
                box.innerHTML = renderMsgThread(d.data, null, true);
            } else {
                var viewAs = el('ad-msg-viewas') ? el('ad-msg-viewas').value : null;
                box.className = 'ad-msg-list ad-msg-thread-wrap';
                box.innerHTML = renderMsgThread(d.data, viewAs, false);
            }
        });
    }

    function renderMoments() {
        api('GET', '/api/admin/moments?page=0&size=100').then(function (d) {
            if (!d || d.code !== 0 || !Array.isArray(d.data)) { el('ad-main').innerHTML = '<div class="ad-empty">加载失败</div>'; return; }
            if (d.data.length === 0) { el('ad-main').innerHTML = '<div class="ad-empty">暂无动态</div>'; return; }
            var html = '<div class="ad-section-title">朋友圈动态</div>';
            html += d.data.map(function (m) {
                var imgs = '';
                try {
                    var arr = m.images ? JSON.parse(m.images) : [];
                    if (Array.isArray(arr)) imgs = arr.map(function (u) {
                        return '<img class="ad-moment-img" src="' + esc(u) + '" onclick="window.open(\'' + esc(u) + '\')">';
                    }).join('');
                } catch (e) {}
                var rejected = m.status === 'REJECTED';
                var statusBadge = rejected
                    ? '<span class="ad-badge danger">已打回</span>'
                    : '<span class="ad-badge ok">正常</span>';
                var actions = rejected
                    ? '<button class="ad-btn ghost" onclick="AdminApp.restoreMoment(' + m.id + ')">撤回打回</button>'
                    : '<button class="ad-btn warn" onclick="AdminApp.openReject(' + m.id + ')">打回整改</button>';
                return '<div class="ad-moment' + (rejected ? ' rejected' : '') + '" id="ad-moment-' + m.id + '">' +
                    '<div class="ad-moment-head"><b>' + esc(m.authorName) + '</b> ' + statusBadge +
                    ' <span class="ad-badge">' + esc(m.visibility) + '</span>' +
                    ' <span class="ad-msg-time">' + esc(fmtTime(m.createTime)) + '</span></div>' +
                    '<div class="ad-moment-content">' + esc(m.content) + '</div>' +
                    (imgs ? '<div class="ad-moment-imgs">' + imgs + '</div>' : '') +
                    (rejected && m.rejectReason ? '<div class="ad-reject-reason">打回原因：' + esc(m.rejectReason) + '</div>' : '') +
                    '<div class="ad-moment-actions">' +
                    '<button class="ad-btn" onclick="AdminApp.toggleMomentComments(' + m.id + ', this)">查看评论</button>' +
                    actions + '</div>' +
                    '<div class="ad-moment-comments hidden" id="ad-moment-comments-' + m.id + '"></div>' +
                    '</div>';
            }).join('');
            el('ad-main').innerHTML = html;
        });
    }

    function toggleMomentComments(id, btn) {
        var box = el('ad-moment-comments-' + id);
        if (!box) return;
        if (!box.classList.contains('hidden')) { box.classList.add('hidden'); btn.textContent = '查看评论'; return; }
        btn.textContent = '加载中...';
        api('GET', '/api/admin/moments/' + id + '/comments').then(function (d) {
            btn.textContent = '收起评论';
            if (!d || d.code !== 0 || !Array.isArray(d.data) || d.data.length === 0) {
                box.innerHTML = '<div class="ad-empty" style="padding:16px">暂无评论</div>';
                box.classList.remove('hidden');
                return;
            }
            box.innerHTML = d.data.map(function (c) {
                var av = c.author && c.author.avatar;
                var avatar = av ? '<img class="avatar" src="' + esc(av) + '" alt="">'
                    : '<div class="avatar" style="background:var(--accent,#07c160);color:#fff;display:flex;align-items:center;justify-content:center;font-weight:600">' + esc((c.author && c.author.nickname || '?').slice(0, 1)) + '</div>';
                var content = c.content ? esc(c.content) : '';
                var imgs = '';
                if (Array.isArray(c.images) && c.images.length) {
                    imgs = c.images.map(function (u) { return '<img class="ad-moment-img" src="' + esc(u) + '" onclick="window.open(\'' + esc(u) + '\')">'; }).join('');
                }
                var reply = c.replyToName ? ' <span class="ad-reply">回复 ' + esc(c.replyToName) + '</span>' : '';
                return '<div class="ad-comment">' + avatar +
                    '<div class="ad-comment-body"><div class="ad-comment-head"><b>' + esc(c.author ? c.author.nickname : '?') + '</b>' + reply +
                    ' <span class="ad-msg-time">' + esc(fmtTime(c.createTime)) + '</span></div>' +
                    (content ? '<div class="ad-comment-content">' + content + '</div>' : '') +
                    (imgs ? '<div class="ad-moment-imgs">' + imgs + '</div>' : '') + '</div></div>';
            }).join('');
            box.classList.remove('hidden');
        }).catch(function () {
            btn.textContent = '查看评论';
            box.innerHTML = '<div class="ad-empty" style="padding:16px">加载失败</div>';
            box.classList.remove('hidden');
        });
    }

    var _rejectId = null;
    function openReject(id) {
        _rejectId = id;
        var ta = el('ad-reject-reason');
        if (ta) ta.value = '';
        el('ad-reject-modal').classList.remove('hidden');
    }
    function cancelReject() {
        _rejectId = null;
        el('ad-reject-modal').classList.add('hidden');
    }
    function confirmReject() {
        if (_rejectId == null) return;
        var reason = el('ad-reject-reason') ? el('ad-reject-reason').value.trim() : '';
        api('POST', '/api/admin/moments/' + _rejectId + '/reject', { reason: reason }).then(function (d) {
            cancelReject();
            if (d && d.code === 0) { rerenderCurrent(); }
            else { alert(d && d.message ? d.message : '操作失败'); }
        }).catch(function (e) {
            cancelReject();
            alert('操作失败：' + (e && e.message ? e.message : ''));
        });
    }

    /** 重新渲染当前激活的视图（打回/通过操作后刷新对应列表） */
    function rerenderCurrent() {
        var active = document.querySelector('.ad-nav-item.active');
        var v = active ? active.getAttribute('data-view') : 'moments';
        if (v === 'review') renderReview();
        else renderMoments();
    }
    async function restoreMoment(id) {
        if (!await confirmModal('确定撤回该动态的打回？撤回后动态将恢复正常展示。', { danger: true })) return;
        api('POST', '/api/admin/moments/' + id + '/restore').then(function (d) {
            if (d && d.code === 0) { renderMoments(); }
            else { alert(d && d.message ? d.message : '操作失败'); }
        });
    }

    /* ===== AI 助手管理（role=AI 独立类型，仅管理员可增改） ===== */
    function renderAssistant() {
        api('GET', '/api/admin/assistants').then(function (d) {
            var list = (d && d.code === 0 && Array.isArray(d.data)) ? d.data : [];
            var html = '<div class="ad-section-title">AI 助手管理</div>' +
                '<div class="ad-hint">AI 助手是独立的 role=AI 账户，普通用户不可搜索、加好友或改资料。' +
                '默认助手（id=1）为所有用户共享，在此修改后全员立即生效。新建/编辑仅管理员可用。</div>';
            if (!list.length) {
                html += '<div class="ad-empty">暂无 AI 助手</div>';
            } else {
                html += '<div class="ad-assistant-grid">';
                list.forEach(function (a) {
                    var enabled = a.enabled !== false;
                    var status = a.status || 'ONLINE';
                    var statusText = { ONLINE: '在线', BUSY: '忙碌', OFFLINE: '离线', MAINTENANCE: '维护中' }[status] || status;
                    var avatarHtml = a.avatar
                        ? '<img class="ad-as-avatar" src="' + esc(a.avatar) + '" alt="">'
                        : '<div class="ad-as-avatar ad-as-avatar-ph">' + esc((a.name || 'A').charAt(0)) + '</div>';
                    html += '<div class="ad-as-card' + (enabled ? '' : ' disabled') + '">' +
                        '<div class="ad-as-card-top">' + avatarHtml +
                        '<div class="ad-as-meta">' +
                        '<div class="ad-as-name">' + esc(a.name || '未命名') + ' <span class="ad-as-badge">AI</span></div>' +
                        '<div class="ad-as-sub">' + esc(a.signature || '你的智能伙伴') + '</div>' +
                        '</div></div>' +
                        '<div class="ad-as-tags">' +
                        '<span class="ad-as-tag ' + (enabled ? 'on' : 'off') + '">' + (enabled ? '已启用' : '已禁用') + '</span>' +
                        '<span class="ad-as-tag status-' + status.toLowerCase() + '">' + statusText + '</span>' +
                        (a.isDefault === true ? '<span class="ad-as-tag default">默认助手</span>' : '') +
                        '</div>' +
                        '<div class="ad-as-account-line">账号：' + esc(a.account || '(未设置)') + '</div>' +
                        '<div class="ad-as-actions">' +
                        '<button class="mc-btn mc-btn-soft" onclick="AdminApp.editAssistant(' + a.id + ')">编辑</button>' +
                        '<button class="mc-btn mc-btn-danger-ghost" onclick="AdminApp.deleteAssistant(' + a.id + ')">删除</button>' +
                        '</div></div>';
                });
                html += '</div>';
            }
            html += '<div class="ad-as-newbar"><button class="mc-btn mc-btn-primary" onclick="AdminApp.newAssistant()">＋ 新建 AI 助手</button></div>';
            html += '<div id="ad-as-editor"></div>';
            el('ad-main').innerHTML = html;
        }).catch(function () {
            el('ad-main').innerHTML = '<div class="ad-empty">加载 AI 助手失败</div>';
        });
    }

    /** 打开编辑表单（按 id 拉取当前配置并渲染表单） */
    function editAssistant(id) {
        api('GET', '/api/admin/assistants').then(function (d) {
            var list = (d && d.code === 0 && Array.isArray(d.data)) ? d.data : [];
            var a = list.filter(function (x) { return x.id === id; })[0];
            if (!a) { alert('助手不存在'); return; }
            renderAssistantForm(a, false);
        });
    }

    /** 打开新建表单 */
    function newAssistant() {
        renderAssistantForm({ id: null, name: '', enabled: true, status: 'ONLINE', signature: '', prompt: '', avatar: '' }, true);
    }

    /** 渲染编辑/新建表单（isNew=true 为新建），分区清晰、留白舒适 */
    function renderAssistantForm(a, isNew) {
        var enabled = a.enabled !== false;
        var status = a.status || 'ONLINE';
        var isDefault = a.isDefault === true;
        var html = '<div class="ad-as-formcard">' +
            '<div class="ad-as-form-head">' + (isNew ? '新建 AI 助手' : '编辑 AI 助手') +
            (a.id ? ' <span class="ad-as-form-id">#id ' + a.id + '</span>' : '') + '</div>' +
            '<div class="ad-as-form-body">' +
            '<div class="ad-as-field"><label>名称</label>' +
            '<input id="ad-as-name" class="ad-input" placeholder="如：moyo 助手" value="' + esc(a.name || '') + '"></div>' +
            '<div class="ad-as-field"><label>账号（至少 10 位，可手填或生成）</label>' +
            '<div class="ad-as-account-row">' +
            '<input id="ad-as-account" class="ad-input" placeholder="留空则自动生成 10 位账号" value="' + esc(a.account || '') + '">' +
            '<button type="button" class="mc-btn mc-btn-soft" onclick="AdminApp.genAssistantAccount()">生成账号</button>' +
            '</div></div>' +
            '<div class="ad-as-field"><label>头像</label>' +
            '<div class="ad-as-avatar-uploader" title="点击上传头像" onclick="document.getElementById(\'ad-as-avatar-file\').click()">' +
            (a.avatar ? '<img class="ad-as-avatar-img" id="ad-as-avatar-img" src="' + esc(a.avatar) + '" alt="">'
                       : '<div class="ad-as-avatar-ph" id="ad-as-avatar-img">🤖</div>') +
            '<div class="ad-as-avatar-cam">📷</div>' +
            '<input type="file" id="ad-as-avatar-file" class="hidden" accept="image/*" onchange="AdminApp.onAssistantAvatarPick(this)">' +
            '</div>' +
            '<div class="ad-as-avatar-tip">点击头像框即可上传图片，或手动粘贴图片地址</div>' +
            '<input id="ad-as-avatar" class="ad-input" placeholder="https://… 留空则显示首字母" value="' + esc(a.avatar || '') + '">' +
            '</div>' +
            '<div class="ad-as-field"><label>启用状态</label>' +
            '<label class="ad-switch"><input type="checkbox" id="ad-as-enabled" ' + (enabled ? 'checked' : '') + '> ' +
            '<span class="ad-switch-track"><span class="ad-switch-thumb"></span></span>' +
            '<span class="ad-switch-label">' + (enabled ? '启用中' : '已停用') + '</span></label></div>' +
            '<div class="ad-as-field"><label>设为默认助手</label>' +
            '<label class="ad-switch"><input type="checkbox" id="ad-as-default" ' + (isDefault ? 'checked' : '') + '> ' +
            '<span class="ad-switch-track"><span class="ad-switch-thumb"></span></span>' +
            '<span class="ad-switch-label">设为默认后，新用户自动添加、删除后自动切换</span></label></div>' +
            '<div class="ad-as-field"><label>在线状态</label>' +
            '<select id="ad-as-status" class="ad-input">' +
            '<option value="ONLINE"' + (status === 'ONLINE' ? ' selected' : '') + '>在线</option>' +
            '<option value="BUSY"' + (status === 'BUSY' ? ' selected' : '') + '>忙碌</option>' +
            '<option value="OFFLINE"' + (status === 'OFFLINE' ? ' selected' : '') + '>离线</option>' +
            '<option value="MAINTENANCE"' + (status === 'MAINTENANCE' ? ' selected' : '') + '>维护中</option>' +
            '</select></div>' +
            '<div class="ad-as-field col"><label>个性签名</label>' +
            '<input id="ad-as-sign" class="ad-input" placeholder="一句话简介" value="' + esc(a.signature || '') + '"></div>' +
            '<div class="ad-as-field col"><label>人设提示词（决定助手性格与能力）</label>' +
            '<textarea id="ad-as-prompt" class="ad-textarea" rows="6" placeholder="例如：你是用户的贴心伙伴，语气活泼，擅长帮写文案、规划学习…">' + esc(a.prompt || '') + '</textarea></div>' +
            (isNew ? '<div class="ad-as-field col"><label>登录密码（底层账户，可选）</label>' +
                '<input id="ad-as-pwd" class="ad-input" type="password" placeholder="留空使用默认密码 ai@2024"></div>' : '') +
            '</div>' +
            '<div class="ad-as-form-foot">' +
            '<button class="mc-btn mc-btn-primary" onclick="AdminApp.saveAssistant(' + (isNew ? 'true' : 'false') + ',' + (a.id || 'null') + ')">保存</button>' +
            '<button class="mc-btn mc-btn-ghost" onclick="AdminApp.cancelAssistant()">取消</button>' +
            '<span id="ad-as-msg" class="ad-as-msg"></span></div>' +
            '</div>';
        el('ad-as-editor').innerHTML = html;
        el('ad-as-editor').scrollIntoView({ behavior: 'smooth' });
    }

    /** 生成账号按钮：调用后端生成 >=10 位唯一账号并填入输入框 */
    function genAssistantAccount() {
        api('GET', '/api/admin/generate-account').then(function (d) {
            if (d && d.code === 0 && d.data) {
                var inp = document.getElementById('ad-as-account');
                if (inp) inp.value = d.data;
            } else {
                alert((d && d.message) || '生成账号失败');
            }
        }).catch(function () { alert('生成账号失败'); });
    }

    /** 保存：新建走 POST /assistants，编辑走 PUT /assistants/{id} */
    function saveAssistant(isNew, id) {
        var msg = el('ad-as-msg');
        if (msg) msg.textContent = '保存中…';
        var cfg = {
            name: document.getElementById('ad-as-name').value,
            avatar: AdminApp._assistantAvatarUrl != null ? AdminApp._assistantAvatarUrl : document.getElementById('ad-as-avatar').value,
            enabled: document.getElementById('ad-as-enabled').checked,
            isDefault: document.getElementById('ad-as-default').checked,
            status: document.getElementById('ad-as-status').value,
            signature: document.getElementById('ad-as-sign').value,
            prompt: document.getElementById('ad-as-prompt').value
        };
        var accEl = document.getElementById('ad-as-account');
        if (accEl && accEl.value.trim()) cfg.account = accEl.value.trim();
        if (isNew) {
            var pwd = document.getElementById('ad-as-pwd');
            cfg.password = pwd ? pwd.value : '';
        }
        var call = isNew
            ? api('POST', '/api/admin/assistants', cfg)
            : api('PUT', '/api/admin/assistants/' + id, cfg);
        call.then(function (d) {
            if (msg) msg.textContent = (d && d.code === 0) ? '已保存' : ((d && d.message) || '保存失败');
            if (d && d.code === 0) { AdminApp._assistantAvatarUrl = null; setTimeout(renderAssistant, 600); }
        }).catch(function () {
            if (msg) msg.textContent = '保存失败';
        });
    }

    function cancelAssistant() {
        el('ad-as-editor').innerHTML = '';
        AdminApp._assistantAvatarUrl = null;
    }

    /** 头像上传：照搬个人信息换头像逻辑（App.onAvatarPick），选中图片即上传并刷新预览 */
    function onAssistantAvatarPick(input) {
        var file = input.files && input.files[0];
        if (!file) return;
        var msg = el('ad-as-msg');
        if (msg) msg.textContent = '上传中…';
        uploadFile(file, 'assistant/avatar').then(function (data) {
            if (data && data.code === 0 && data.data && data.data.url) {
                AdminApp._assistantAvatarUrl = data.data.url;
                var wrap = document.querySelector('.ad-as-avatar-uploader');
                if (wrap) {
                    var img = wrap.querySelector('#ad-as-avatar-img');
                    if (!img || img.tagName !== 'IMG') {
                        img = document.createElement('img');
                        img.id = 'ad-as-avatar-img';
                        img.className = 'ad-as-avatar-img';
                        var cam = wrap.querySelector('.ad-as-avatar-cam');
                        wrap.insertBefore(img, cam);
                        var ph = wrap.querySelector('.ad-as-avatar-ph');
                        if (ph) ph.style.display = 'none';
                    }
                    img.src = data.data.url;
                    img.style.display = 'block';
                }
                var urlBox = document.getElementById('ad-as-avatar');
                if (urlBox) urlBox.value = data.data.url;
                if (msg) msg.textContent = '头像已上传，记得点保存';
            } else {
                if (msg) msg.textContent = (data && data.message) || '头像上传失败';
            }
        }).catch(function () {
            if (msg) msg.textContent = '头像上传失败';
        });
        input.value = '';
    }

    /** 删除 AI 助手（默认助手 id=1 不允许删除，由后端拦截） */
    function deleteAssistant(id) {
        if (!confirm('确定删除该 AI 助手？关联的账户也将一并删除。默认助手(id=1)不可删除。')) return;
        api('DELETE', '/api/admin/assistants/' + id).then(function (d) {
            if (d && d.code === 0) { renderAssistant(); }
            else { alert(d && d.message ? d.message : '删除失败'); }
        }).catch(function () {
            alert('删除失败');
        });
    }

    /* ===== 内容审核 ===== */
    function renderReview() {
        var html = '<div class="ad-section-title">内容审核</div>' +
            '<div class="ad-review-mode" id="ad-review-mode">' +
            '<span class="ad-review-mode-label">审核模式：</span>' +
            '<label class="ad-radio"><input type="radio" name="ad-review-mode" value="AUTO" onchange="AdminApp.setReviewMode(\'AUTO\')"> 自动审核（直接发布）</label>' +
            '<label class="ad-radio"><input type="radio" name="ad-review-mode" value="MANUAL" onchange="AdminApp.setReviewMode(\'MANUAL\')"> 人工审核（待审核）</label>' +
            '<label class="ad-radio"><input type="radio" name="ad-review-mode" value="AI" onchange="AdminApp.setReviewMode(\'AI\')"> AI 审核（不通过转人工复审）</label>' +
            '<span id="ad-review-mode-msg" class="ad-hint"></span>' +
            '</div>' +
            '<div class="ad-hint">AI 审核模式下，发布内容先由 moyo 助手做安全判定：通过直接公开，不通过进入「待审核」队列并附 AI 意见；用户可在「我的」页申请人工复审。人工审核模式则全部进入待审核队列由管理员判定。</div>' +
            '<div class="ad-review-filter" id="ad-review-filter">' +
            '<span class="ad-filter-label">查看：</span>' +
            '<button class="ad-filter-btn" data-f="pending" onclick="AdminApp.setReviewFilter(\'pending\')">待审核队列</button>' +
            '<button class="ad-filter-btn" data-f="ai_fail" onclick="AdminApp.setReviewFilter(\'ai_fail\')">AI 打回</button>' +
            '<button class="ad-filter-btn" data-f="all" onclick="AdminApp.setReviewFilter(\'all\')">全部 AI 审核内容</button>' +
            '<button class="ad-filter-btn" data-f="manual_rejected" onclick="AdminApp.setReviewFilter(\'manual_rejected\')">打回列表</button>' +
            '</div>' +
            '<div id="ad-review-list" class="ad-moment-list"></div>';
        el('ad-main').innerHTML = html;
        loadReviewMode();
        AdminApp._reviewFilter = 'pending';
        AdminApp.setReviewFilter('pending');
    }

    function setReviewFilter(f) {
        AdminApp._reviewFilter = f;
        document.querySelectorAll('#ad-review-filter .ad-filter-btn').forEach(function (b) {
            b.classList.toggle('active', b.getAttribute('data-f') === f);
        });
        loadReviewList();
    }

    function loadReviewMode() {
        api('GET', '/api/admin/review/mode').then(function (d) {
            if (d && d.code === 0) {
                var m = d.data || 'AUTO';
                document.querySelectorAll('input[name="ad-review-mode"]').forEach(function (r) {
                    r.checked = (r.value === m);
                });
            }
        });
    }

    function setReviewMode(mode) {
        var msg = el('ad-review-mode-msg');
        if (msg) msg.textContent = '切换中...';
        api('POST', '/api/admin/review/mode', { mode: mode }).then(function (d) {
            if (msg) msg.textContent = (d && d.code === 0) ? ('已切换为 ' + mode) : ((d && d.message) || '切换失败');
            if (d && d.code === 0) loadReviewList();
        }).catch(function () {
            if (msg) msg.textContent = '切换失败';
        });
    }

    function loadReviewList() {
        var box = el('ad-review-list');
        if (!box) return;
        var f = AdminApp._reviewFilter || 'pending';
        api('GET', '/api/admin/review?filter=' + encodeURIComponent(f)).then(function (d) {
            if (!d || d.code !== 0 || !Array.isArray(d.data) || d.data.length === 0) {
                var emptyTxt = (f === 'ai_fail') ? '暂无 AI 打回的内容'
                    : (f === 'manual_rejected') ? '打回列表为空（人工打回的内容会显示在这里）'
                    : (f === 'all') ? '暂无 AI 审核过的内容' : '暂无需审核的内容';
                box.innerHTML = '<div class="ad-empty">' + emptyTxt + '</div>';
                return;
            }
            box.innerHTML = d.data.map(function (m) {
                var imgs = '';
                try {
                    var arr = m.images ? JSON.parse(m.images) : [];
                    if (Array.isArray(arr)) imgs = arr.map(function (u) {
                        return '<img class="ad-moment-img" src="' + esc(u) + '" onclick="window.open(\'' + esc(u) + '\')">';
                    }).join('');
                } catch (e) {}
                var statusBadge = '';
                if (m.status === 'NORMAL') statusBadge = '<span class="ad-badge ok">已公开</span>';
                else if (m.status === 'REJECTED') statusBadge = '<span class="ad-badge danger">已打回</span>';
                else if (m.status === 'AI_REVIEWING') statusBadge = '<span class="ad-badge reviewing"><span class="mc-spinner"></span>AI 审核中</span>';
                else if (m.status === 'MANUAL_REVIEWING') statusBadge = '<span class="ad-badge warn">人工审核中</span>';
                else statusBadge = '<span class="ad-badge warn">待审核</span>';
                var aiBadge = '';
                if (m.aiReview === 'FAIL') {
                    aiBadge = '<span class="ad-badge danger">AI 不通过</span>';
                } else if (m.aiReview === 'PASS') {
                    aiBadge = '<span class="ad-badge ok">AI 通过</span>';
                } else if (m.aiReview) {
                    aiBadge = '<span class="ad-badge">' + esc(m.aiReview) + '</span>';
                }
                var manualBadge = (m.manualReview) ? '<span class="ad-badge warn">已申请人工复审</span>' : '';
                var aiBlock = (m.aiSuggestion)
                    ? '<div class="ad-moment-ai"><div class="ad-moment-ai-head"><span class="ad-moment-ai-ico">AI</span>审核意见</div>' +
                      '<div class="ad-moment-ai-body">' + esc(m.aiSuggestion) + '</div></div>'
                    : '';
                // 打回列表：展示人工打回原因（rejectReason）
                var rejectBlock = (isRejectedList && m.rejectReason)
                    ? '<div class="ad-reject-reason">打回原因：' + esc(m.rejectReason) + '</div>'
                    : '';
                // 「待审核队列」(pending)：保留「通过 / 打回」双按钮（人工审核语义）。
                // 「全部 AI 审核内容 / AI 打回」(all / ai_fail)：AI 审核通过的内容不需要「通过」按钮，
                //   只提供「打回」按钮；打回后该条从 AI 审核列表移除（ai_review 置空，转入人工 PENDING 队列）。
                // 「打回列表」(manual_rejected)：仅人工打回的内容，只能查看与删除，不可重复打回。
                var isPendingQueue = (f === 'pending');
                var isRejectedList = (f === 'manual_rejected');
                var actions;
                if (isRejectedList) {
                    actions = '<button class="mc-btn mc-btn-ghost" onclick="AdminApp.toggleMomentComments(' + m.id + ', this)">查看</button>' +
                              '<button class="mc-btn mc-btn-danger-ghost" onclick="AdminApp.deleteMoment(' + m.id + ')">删除</button>';
                } else if (isPendingQueue) {
                    actions = '<button class="mc-btn mc-btn-primary" onclick="AdminApp.approveMoment(' + m.id + ')">通过</button>' +
                              '<button class="mc-btn mc-btn-danger-ghost" onclick="AdminApp.openReject(' + m.id + ')">打回</button>';
                } else {
                    actions = '<button class="mc-btn mc-btn-danger-ghost" onclick="AdminApp.openReject(' + m.id + ')">打回</button>';
                }
                return '<div class="ad-moment" id="ad-review-' + m.id + '">' +
                    '<div class="ad-moment-head"><div class="ad-moment-author"><b>' + esc(m.authorName) + '</b>' +
                    '<span class="ad-moment-meta">' + esc(m.visibility) + ' · ' + esc(fmtTime(m.createTime)) + '</span></div>' +
                    '<div class="ad-moment-badges">' + statusBadge + aiBadge + manualBadge + '</div></div>' +
                    '<div class="ad-moment-content">' + esc(m.content) + '</div>' +
                    aiBlock +
                    rejectBlock +
                    (imgs ? '<div class="ad-moment-imgs">' + imgs + '</div>' : '') +
                    '<div class="ad-moment-actions">' + actions + '</div></div>';
            }).join('');
        });
    }

    async function approveMoment(id) {
        if (!await confirmModal('确定通过这条动态？通过后将对所有人公开。')) return;
        api('POST', '/api/admin/moments/' + id + '/approve').then(function (d) {
            if (d && d.code === 0) { loadReviewList(); }
            else { alert(d && d.message ? d.message : '操作失败'); }
        });
    }

    /** 删除动态（仅「打回列表」中使用）：彻底删除该条被打回的内容，不可恢复 */
    async function deleteMoment(id) {
        if (!await confirmModal('确定删除这条被打回的动态？删除后不可恢复，且不会出现在任何审核列表中。', { danger: true })) return;
        api('DELETE', '/api/admin/moments/' + id).then(function (d) {
            if (d && d.code === 0) { loadReviewList(); }
            else { alert(d && d.message ? d.message : '操作失败'); }
        });
    }

    function renderGroups() {
        api('GET', '/api/admin/groups').then(function (d) {
            if (!d || d.code !== 0 || !Array.isArray(d.data)) { el('ad-main').innerHTML = '<div class="ad-empty">加载失败</div>'; return; }
            if (d.data.length === 0) { el('ad-main').innerHTML = '<div class="ad-empty">暂无群聊</div>'; return; }
            var html = '<div class="ad-section-title">群聊管理</div><div class="ad-table-wrap"><table class="ad-table"><thead><tr>' +
                '<th>群ID</th><th>群名</th><th>群主</th><th>状态</th><th>创建时间</th><th>成员</th></tr></thead><tbody>';
            html += d.data.map(function (g) {
                var del = g.deleted ? '<span class="ad-badge danger">已解散</span>' : '<span class="ad-badge ok">正常</span>';
                return '<tr><td>' + esc(g.id) + '</td><td>' + esc(g.name) + '</td><td>' + esc(g.ownerName) + '</td><td>' + del + '</td><td>' + esc(fmtTime(g.createTime)) + '</td>' +
                    '<td><button class="ad-btn" onclick="AdminApp.toggleGroupMembers(' + g.id + ', this)">查看成员</button>' +
                    '<div class="ad-group-members hidden" id="ad-group-members-' + g.id + '"></div></td></tr>';
            }).join('');
            html += '</tbody></table></div>';
            el('ad-main').innerHTML = html;
        });
    }

    function toggleGroupMembers(id, btn) {
        var box = el('ad-group-members-' + id);
        if (!box) return;
        if (!box.classList.contains('hidden')) { box.classList.add('hidden'); btn.textContent = '查看成员'; return; }
        btn.textContent = '加载中...';
        api('GET', '/api/admin/groups/' + id + '/members').then(function (d) {
            btn.textContent = '收起成员';
            if (!d || d.code !== 0 || !Array.isArray(d.data) || d.data.length === 0) {
                box.innerHTML = '<div class="ad-empty" style="padding:14px">暂无成员</div>';
                box.classList.remove('hidden');
                return;
            }
            box.innerHTML = '<div class="ad-member-list">' + d.data.map(function (m) {
                var av = m.avatar;
                var avatar = av ? '<img class="avatar" src="' + esc(av) + '" alt="">'
                    : '<div class="avatar" style="background:var(--accent,#07c160);color:#fff;display:flex;align-items:center;justify-content:center;font-weight:600">' + esc((m.nickname || '?').slice(0, 1)) + '</div>';
                var role = m.role === 'OWNER' ? '<span class="ad-badge ok">群主</span>' : '<span class="ad-badge">成员</span>';
                var on = m.online ? '<span class="ad-dot" style="background:#07c160"></span>在线' : '<span class="ad-dot" style="background:#bbb"></span>离线';
                return '<div class="ad-member">' + avatar +
                    '<div class="ad-member-info"><div class="ad-member-name">' + esc(m.nickname) + ' ' + role + '</div>' +
                    '<div class="ad-member-sub">#' + esc(m.account) + ' · ' + on + '</div></div></div>';
            }).join('') + '</div>';
            box.classList.remove('hidden');
        }).catch(function () {
            btn.textContent = '查看成员';
            box.innerHTML = '<div class="ad-empty" style="padding:14px">加载失败</div>';
            box.classList.remove('hidden');
        });
    }

    /* 通知管理：发布表单（标题/内容/接收范围/有效时长）+ 已发通知列表 */
    function renderNotices() {
        var html = '<div class="ad-section-title">通知管理</div>' +
            '<div class="ad-notice-form">' +
            '<div class="ad-form-row"><label>标题</label><input id="ad-nt-title" class="ad-input" placeholder="通知标题（必填）"></div>' +
            '<div class="ad-form-row"><label>内容</label><textarea id="ad-nt-content" class="ad-input" rows="3" placeholder="通知正文（可选）"></textarea></div>' +
            '<div class="ad-form-row"><label>接收范围</label>' +
            '<label class="ad-radio"><input type="radio" name="ad-nt-target" value="ALL" checked onchange="AdminApp.onNoticeTargetChange()"> 所有人</label>' +
            '<label class="ad-radio"><input type="radio" name="ad-nt-target" value="SPECIFIED" onchange="AdminApp.onNoticeTargetChange()"> 指定人</label>' +
            '</div>' +
            '<div class="ad-form-row hidden" id="ad-nt-users-row"><label>指定接收人</label>' +
            '<select id="ad-nt-users" class="ad-input" multiple size="5"></select>' +
            '<span class="ad-hint">按住 Ctrl/⌘ 多选</span></div>' +
            '<div class="ad-form-row"><label>有效时长</label>' +
            '<input id="ad-nt-duration" class="ad-input ad-input-sm" type="number" min="0" placeholder="0"> ' +
            '<select id="ad-nt-unit" class="ad-input ad-input-sm">' +
            '<option value="1">分钟</option><option value="60">小时</option><option value="1440">天</option></select>' +
            '<span class="ad-hint">0 / 留空 = 永久有效</span></div>' +
            '<div class="ad-form-row"><label>阅读时长</label>' +
            '<input id="ad-nt-wait" class="ad-input ad-input-sm" type="number" min="0" placeholder="0"> ' +
            '<select id="ad-nt-wait-unit" class="ad-input ad-input-sm">' +
            '<option value="1">秒</option><option value="60">分钟</option></select>' +
            '<span class="ad-hint">0 / 留空 = 不限制（用户可立即点「我知道了」）；设置后用户须等待超过该时长才能确认</span></div>' +
            '<div class="ad-form-row"><button class="ad-btn" onclick="AdminApp.publishNotice()">发布通知</button>' +
            '<span id="ad-nt-msg" class="ad-hint"></span></div>' +
            '</div>' +
            '<div class="ad-section-title" style="margin-top:18px">已发通知</div>' +
            '<div id="ad-nt-list" class="ad-msg-list"></div>';
        el('ad-main').innerHTML = html;
        // 载入用户列表供「指定人」多选
        api('GET', '/api/admin/users').then(function (d) {
            if (d && d.code === 0 && Array.isArray(d.data)) {
                el('ad-nt-users').innerHTML = d.data.map(function (u) {
                    return '<option value="' + u.id + '">' + esc(u.nickname || u.username) + '(#' + esc(u.account) + ')</option>';
                }).join('');
            }
        });
        loadNoticeList();
    }

    function onNoticeTargetChange() {
        var type = document.querySelector('input[name="ad-nt-target"]:checked').value;
        var row = el('ad-nt-users-row');
        if (row) row.classList.toggle('hidden', type !== 'SPECIFIED');
    }

    function publishNotice() {
        var title = el('ad-nt-title').value.trim();
        var content = el('ad-nt-content').value.trim();
        var targetType = document.querySelector('input[name="ad-nt-target"]:checked').value;
        var targetIds = [];
        if (targetType === 'SPECIFIED') {
            var sel = el('ad-nt-users');
            for (var i = 0; i < sel.options.length; i++) {
                if (sel.options[i].selected) targetIds.push(Number(sel.options[i].value));
            }
        }
        var durVal = parseFloat(el('ad-nt-duration').value);
        var unit = Number(el('ad-nt-unit').value);
        var durationMinutes = (!durVal || durVal <= 0) ? 0 : Math.round(durVal * unit);
        var waitVal = parseFloat(el('ad-nt-wait').value);
        var waitUnit = Number(el('ad-nt-wait-unit').value);
        var readWaitSeconds = (!waitVal || waitVal <= 0) ? 0 : Math.round(waitVal * waitUnit);
        var msg = el('ad-nt-msg');
        msg.textContent = '发布中...';
        api('POST', '/api/admin/notice', {
            title: title,
            content: content,
            targetType: targetType,
            targetIds: targetIds,
            durationMinutes: durationMinutes,
            readWaitSeconds: readWaitSeconds
        }).then(function (d) {
            if (d && d.code === 0) {
                msg.textContent = '已发布';
                el('ad-nt-title').value = '';
                el('ad-nt-content').value = '';
                loadNoticeList();
            } else {
                msg.textContent = (d && d.message) || '发布失败';
            }
        }).catch(function () { msg.textContent = '发布失败'; });
    }

    function loadNoticeList() {
        api('GET', '/api/admin/notices').then(function (d) {
            var box = el('ad-nt-list');
            if (!box) return;
            if (!d || d.code !== 0 || !Array.isArray(d.data) || d.data.length === 0) {
                box.innerHTML = '<div class="ad-empty">暂无通知</div>';
                return;
            }
            box.innerHTML = d.data.map(function (n) {
                var scope = n.targetType === 'ALL' ? '全员' : ('指定 ' + (n.targetIds ? n.targetIds.split(',').length : 0) + ' 人');
                var expire = n.expireAt ? ('有效期至 ' + esc(fmtTime(n.expireAt))) : '永久有效';
                var wait = (n.readWaitSeconds && n.readWaitSeconds > 0) ? (' · 阅读时长 ' + n.readWaitSeconds + 's') : '';
                return '<div class="ad-msg-row">' +
                    '<div class="ad-msg-meta"><b>' + esc(n.title) + '</b> <span class="ad-badge">' + scope + '</span>' +
                    ' <span class="ad-msg-time">' + esc(fmtTime(n.createTime)) + '</span></div>' +
                    (n.content ? '<div class="ad-msg-content">' + esc(n.content) + '</div>' : '') +
                    '<div class="ad-msg-time">' + expire + wait + (n.senderName ? ' · 发布者 ' + esc(n.senderName) : '') + '</div>' +
                    '<div class="ad-msg-actions"><button class="ad-btn danger" style="padding:5px 12px;font-size:13px" onclick="AdminApp.revokeNotice(' + n.id + ')">撤销通知</button></div>' +
                    '</div>';
            }).join('');
        });
    }

    async function revokeNotice(id) {
        if (!await confirmModal('确定撤销该通知？撤销后用户端将不再显示，且不可恢复。', { danger: true })) return;
        api('DELETE', '/api/admin/notice/' + id).then(function (d) {
            if (d && d.code === 0) { loadNoticeList(); }
            else { window.alert((d && d.message) || '撤销失败'); }
        }).catch(function () { window.alert('撤销失败'); });
    }

    function init() {
        if (token) {
            api('GET', '/api/admin/check').then(function (d) {
                if (d && d.code === 0 && d.data === true) { showShell(); showView('overview'); }
                else { logout(); }
            }).catch(function () { logout(); });
        } else {
            showLogin();
        }
    }

    /* ============ MCP 功能配置 ============ */
    function renderMcp() {
        var html = '<div class="ad-section-title">MCP 功能配置</div>' +
            '<div class="ad-hint">管理端可在此新增 / 启停各项 MCP 功能。普通用户悬浮窗「AI 小助手」中仅能看到已启用的功能（如「消息群发助手」）。</div>' +
            '<div class="ad-mcp-actions"><button class="ad-btn" onclick="AdminApp.mcpNew()">+ 新增功能</button></div>' +
            '<div id="mcp-edit-wrap"></div>' +
            '<div id="mcp-list" class="ad-mcp-list"><div class="ad-empty">加载中…</div></div>';
        el('ad-main').innerHTML = html;
        loadMcpList();
    }

    function loadMcpList() {
        var box = el('mcp-list');
        if (!box) return;
        api('GET', '/api/admin/mcp').then(function (d) {
            if (!d || d.code !== 0 || !Array.isArray(d.data)) {
                box.innerHTML = '<div class="ad-empty">加载失败</div>';
                return;
            }
            if (d.data.length === 0) {
                box.innerHTML = '<div class="ad-empty">暂无 MCP 功能，点击「新增功能」创建</div>';
                return;
            }
            box.innerHTML = d.data.map(function (f) {
                var builtin = f.code === 'message_sender';
                return '<div class="ad-mcp-item">' +
                    '<div class="ad-mcp-main">' +
                    '  <div class="ad-mcp-name">' + esc(f.name) + ' <span class="ad-mcp-code">' + esc(f.code) + (builtin ? ' · 内置' : '') + '</span></div>' +
                    '  <div class="ad-mcp-desc">' + esc(f.description || '') + '</div>' +
                    '  <div class="ad-mcp-meta">排序：' + esc(f.sort) + (f.configJson ? ' · 配置：' + esc(f.configJson) : '') + '</div>' +
                    '</div>' +
                    '<div class="ad-mcp-ops">' +
                    '  <label class="ad-switch"><input type="checkbox" ' + (f.enabled ? 'checked' : '') + ' onchange="AdminApp.mcpToggle(' + f.id + ', this.checked)"><span>启用</span></label>' +
                    '  <button class="ad-btn ghost" onclick="AdminApp.mcpEdit(' + f.id + ')">编辑</button>' +
                    '  <button class="ad-btn danger" onclick="AdminApp.mcpDelete(' + f.id + ')">删除</button>' +
                    '</div>' +
                    '</div>';
            }).join('');
        });
    }

    function mcpForm(item) {
        item = item || {};
        var isEdit = !!item.id;
        var html = '<div class="ad-mcp-form">' +
            '<div class="ad-form-row"><label>功能编码(code)</label><input id="mcp-f-code" class="ad-input" value="' + esc(item.code || '') + '" placeholder="唯一编码，如 message_sender" ' + (isEdit ? 'disabled' : '') + '></div>' +
            '<div class="ad-form-row"><label>显示名</label><input id="mcp-f-name" class="ad-input" value="' + esc(item.name || '') + '"></div>' +
            '<div class="ad-form-row"><label>简介</label><textarea id="mcp-f-desc" class="ad-input" rows="2">' + esc(item.description || '') + '</textarea></div>' +
            '<div class="ad-form-row"><label>JSON 配置</label><textarea id="mcp-f-json" class="ad-input" rows="3" placeholder=\'{"maxRecipients":50}\'>' + esc(item.configJson || '') + '</textarea></div>' +
            '<div class="ad-form-row"><label>排序</label><input id="mcp-f-sort" type="number" class="ad-input ad-input-sm" value="' + esc(item.sort != null ? item.sort : 0) + '"></div>' +
            '<div class="ad-form-row"><label>启用</label><input id="mcp-f-enabled" type="checkbox" ' + (item.enabled ? 'checked' : '') + '></div>' +
            '<div class="ad-form-row"><button class="ad-btn" id="mcp-f-save">保存</button> <button class="ad-btn ghost" onclick="AdminApp.mcpCancelEdit()">取消</button> <span id="mcp-f-msg" class="ad-hint"></span></div>' +
            '</div>';
        el('mcp-edit-wrap').innerHTML = html;
        el('mcp-f-save').addEventListener('click', function () { mcpSave(item.id); });
    }

    function mcpNew() { mcpForm(null); }

    function mcpEdit(id) {
        api('GET', '/api/admin/mcp').then(function (d) {
            if (d && d.code === 0 && Array.isArray(d.data)) {
                var item = d.data.find(function (x) { return x.id === id; });
                if (item) mcpForm(item);
            }
        });
    }

    function mcpCancelEdit() { el('mcp-edit-wrap').innerHTML = ''; }

    function mcpSave(id) {
        var code = el('mcp-f-code').value.trim();
        var name = el('mcp-f-name').value.trim();
        var description = el('mcp-f-desc').value.trim();
        var configJson = el('mcp-f-json').value.trim();
        var sort = parseInt(el('mcp-f-sort').value, 10);
        if (isNaN(sort)) sort = 0;
        var enabled = el('mcp-f-enabled').checked;
        var msg = el('mcp-f-msg');
        if (!id && !code) { msg.textContent = '请填写功能编码(code)'; return; }
        var body = { name: name, description: description, configJson: configJson, sort: sort, enabled: enabled };
        if (!id) body.code = code;
        msg.textContent = '保存中…';
        var p = id ? api('PUT', '/api/admin/mcp/' + id, body) : api('POST', '/api/admin/mcp', body);
        p.then(function (r) {
            if (r && r.code === 0) {
                el('mcp-edit-wrap').innerHTML = '';
                loadMcpList();
            } else {
                msg.textContent = (r && r.message) || '保存失败';
            }
        }).catch(function (e) { msg.textContent = '保存失败：' + e.message; });
    }

    function mcpToggle(id, enabled) {
        api('POST', '/api/admin/mcp/' + id + '/toggle', { enabled: !!enabled }).then(function (r) {
            if (!r || r.code !== 0) { loadMcpList(); }
        });
    }

    function mcpDelete(id) {
        confirmModal('确定删除该 MCP 功能吗？此操作不可恢复（内置功能 message_sender 仅可停用，不可删除）。', { danger: true }).then(function (ok) {
            if (!ok) return;
            api('DELETE', '/api/admin/mcp/' + id).then(function (r) {
                if (r && r.code === 0) loadMcpList();
                else confirmModal((r && r.message) || '删除失败', { okText: '知道了' });
            });
        });
    }

    return {
        init: init,
        login: login,
        logout: logout,
        showView: showView,
        loadUsers: loadUsers,
        setFrozen: setFrozen,
        openFreeze: openFreeze,
        confirmFreeze: confirmFreeze,
        cancelFreeze: cancelFreeze,
        offline: offline,
        toggleMsgType: toggleMsgType,
        refreshViewAs: refreshViewAs,
        onMsgChange: onMsgChange,
        loadMessages: loadMessages,
        toggleMomentComments: toggleMomentComments,
        openReject: openReject,
        confirmReject: confirmReject,
        cancelReject: cancelReject,
        restoreMoment: restoreMoment,
        renderReview: renderReview,
        setReviewFilter: setReviewFilter,
        setReviewMode: setReviewMode,
        renderAssistant: renderAssistant,
        editAssistant: editAssistant,
        newAssistant: newAssistant,
        saveAssistant: saveAssistant,
        cancelAssistant: cancelAssistant,
        genAssistantAccount: genAssistantAccount,
        deleteAssistant: deleteAssistant,
        onAssistantAvatarPick: onAssistantAvatarPick,
        approveMoment: approveMoment,
        deleteMoment: deleteMoment,
        toggleGroupMembers: toggleGroupMembers,
        onNoticeTargetChange: onNoticeTargetChange,
        publishNotice: publishNotice,
        revokeNotice: revokeNotice,
        renderMcp: renderMcp,
        mcpNew: mcpNew,
        mcpEdit: mcpEdit,
        mcpDelete: mcpDelete,
        mcpCancelEdit: mcpCancelEdit,
        mcpToggle: mcpToggle
    };
})();

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () { AdminApp.init(); });
} else {
    AdminApp.init();
}
