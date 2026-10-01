/* 程序空间：功能聚合容器。目前内置「学习空间」模块，未来可在此继续添加更多工具。 */
window.Program = (function () {

    // 已上线的功能模块（后续新增功能只改这里即可）
    var modules = [
        {
            key: 'study',
            name: '学习空间',
            desc: 'AI 制定计划 · 知识点互问 · 资料摘要 · 番茄钟专注',
            icon: '📚',
            color: 'linear-gradient(135deg,#07c160,#10aeff)',
            open: function () { if (window.Study) Study.open(); }
        },
        {
            key: 'painting',
            name: '绘画管理',
            desc: '收藏与管理你的绘画作品，支持上传与删除',
            icon: '🎨',
            color: 'linear-gradient(135deg,#ff9c00,#f76260)',
            open: function () { if (window.Painting) Painting.open(); }
        },
        {
            key: 'capsule',
            name: '时间胶囊',
            desc: '写给未来的自己或好友，到期自动解锁',
            icon: '⏳',
            color: 'linear-gradient(135deg,#7c5cff,#4a8cff)',
            open: function () { if (window.Program) Program.openCapsule(); }
        },
        {
            key: 'points',
            name: '积分亲密度',
            desc: '聊天挖矿得积分、与好友攒亲密度',
            icon: '💎',
            color: 'linear-gradient(135deg,#07c160,#ffb300)',
            open: function () { if (window.Program) Program.openPoints(); }
        },
        {
            key: 'family',
            name: '家庭树',
            desc: '家族族谱 · 亲属关系 · 大事记 · 导出长图与 PDF',
            icon: '🌳',
            color: 'linear-gradient(135deg,#3E5C4B,#A9714B)',
            open: function () { if (window.Family) Family.open(); }
        }
        // 示例：未来新增
        // , { key:'toolbox', name:'工具箱', desc:'...', icon:'🧰', color:'...', open:function(){...} }
    ];

    /** 标记当前是否打开了时间胶囊面板，供 WS 推送（CAPSULE_UNLOCKED）时增量刷新 */
    var capsuleOpen = false;

    /** ① 时间胶囊：进入面板 */
    function openCapsule() {
        var root = document.getElementById('program');
        if (!root) return;
        var tomorrow = new Date(Date.now() + 86400000);
        var minDate = tomorrow.toISOString().slice(0, 10);
        root.innerHTML =
            '<div class="pg-head">' +
                '<button class="pg-back" onclick="Program.render()">‹ 程序空间</button>' +
                '<div class="pg-title">时间胶囊</div>' +
                '<div class="pg-sub">写给未来的自己或好友，到期前加密封存，到点自动解锁</div>' +
            '</div>' +
            '<div class="cap-create">' +
                '<textarea id="cap-content" class="modal-input" rows="3" maxlength="2000" placeholder="写点什么给未来的 TA…（最多 2000 字）"></textarea>' +
                '<div class="cap-row">' +
                    '<select id="cap-receiver" class="modal-input"><option value="">写给自己</option></select>' +
                    '<input id="cap-date" type="date" class="modal-input" min="' + minDate + '" value="' + minDate + '">' +
                    '<button class="pg-btn" id="cap-create-btn" onclick="Program.createCapsule()">封存</button>' +
                '</div>' +
                '<div class="cap-hint">开启日期须晚于今天（最多可预约 50 年后）</div>' +
            '</div>' +
            '<div id="capsule-list" class="capsule-list"><div class="study-loading">加载中…</div></div>';
        capsuleOpen = true;
        // 拉好友列表填充收件人
        Api.friendList().then(function (d) {
            var sel = document.getElementById('cap-receiver');
            if (!sel || !d || d.code !== 0 || !Array.isArray(d.data)) return;
            d.data.forEach(function (f) {
                var name = f.remark || f.nickname || f.name || ('用户' + f.id);
                var opt = document.createElement('option');
                opt.value = f.id;
                opt.textContent = '寄给好友：' + name;
                sel.appendChild(opt);
            });
        }).catch(function () {});
        loadCapsules();
        highlight('capsule');
    }

    /** ① 创建时间胶囊 */
    function createCapsule() {
        var contentEl = document.getElementById('cap-content');
        var receiverEl = document.getElementById('cap-receiver');
        var dateEl = document.getElementById('cap-date');
        var btn = document.getElementById('cap-create-btn');
        if (!contentEl) return;
        var content = contentEl.value.trim();
        if (!content) { App.notify('胶囊内容不能为空'); return; }
        var receiverId = receiverEl && receiverEl.value ? Number(receiverEl.value) : null;
        var openDate = dateEl ? dateEl.value : '';
        if (!openDate) { App.notify('请选择开启日期'); return; }
        if (btn) btn.disabled = true;
        Api.capsuleCreate(receiverId, content, openDate).then(function (d) {
            if (d && d.code === 0) {
                App.notify('⏳ 时间胶囊已封存');
                if (contentEl) contentEl.value = '';
                loadCapsules();
            } else {
                App.notify((d && d.msg) || '创建失败');
            }
        }).catch(function () { App.notify('创建失败'); })
          .then(function () { if (btn) btn.disabled = false; });
    }

    /** ① 拉取并渲染我的胶囊列表（WS 推送解锁时也会调它增量刷新） */
    function loadCapsules() {
        var box = document.getElementById('capsule-list');
        if (!box) { capsuleOpen = false; return; }
        capsuleOpen = true;
        Api.capsuleList().then(function (d) {
            if (!box) return;
            if (!d || d.code !== 0 || !Array.isArray(d.data) || d.data.length === 0) {
                box.innerHTML = '<div class="empty-tip">还没有时间胶囊，写一封给未来的信吧～</div>';
                return;
            }
            box.innerHTML = d.data.map(function (c) {
                var who = c.toSelf ? '写给未来的自己' : (c.mine ? '我寄出的' : '好友寄来');
                var statusHtml, body;
                if (c.unlocked) {
                    statusHtml = '<span class="cap-badge unlocked">已解锁</span>';
                    body = '<div class="cap-content">' + App.escapeHtml(c.content || '') + '</div>';
                } else {
                    statusHtml = '<span class="cap-badge sealed">🔒 封存中</span>';
                    body = '<div class="cap-content cap-sealed">' + App.escapeHtml(c.openTime || '') + ' 解锁</div>';
                }
                return '<div class="cap-item">' +
                    '<div class="cap-item-top"><span class="cap-who">' + App.escapeHtml(who) + '</span>' + statusHtml + '</div>' +
                    body +
                '</div>';
            }).join('');
        }).catch(function () {
            if (box) box.innerHTML = '<div class="empty-tip">加载失败，请重试</div>';
        });
    }

    /** ⑪ 积分与亲密度：进入面板 */
    function openPoints() {
        var root = document.getElementById('program');
        if (!root) return;
        root.innerHTML =
            '<div class="pg-head">' +
                '<button class="pg-back" onclick="Program.render()">‹ 程序空间</button>' +
                '<div class="pg-title">积分与亲密度</div>' +
                '<div class="pg-sub">聊天挖矿：发消息 +2 分，与好友互动攒亲密度</div>' +
            '</div>' +
            '<div id="points-body" class="points-body"><div class="study-loading">加载中…</div></div>';
        highlight('points');
        renderPoints();
    }

    /** ⑪ 渲染积分 / 流水 / 当前会话亲密度 */
    function renderPoints() {
        var box = document.getElementById('points-body');
        if (!box) return;
        Promise.all([
            Api.pointsMe().catch(function () { return null; }),
            Api.pointsLog(20).catch(function () { return null; })
        ]).then(function (arr) {
            if (!box) return;
            var me = (arr[0] && arr[0].code === 0) ? arr[0].data : null;
            var log = (arr[1] && arr[1].code === 0 && Array.isArray(arr[1].data)) ? arr[1].data : [];
            var html = '<div class="pt-balance">' +
                '<div class="pt-num">' + (me ? me.points : '—') + '</div>' +
                '<div class="pt-label">我的积分 · Lv.' + (me ? me.level : '—') + '</div>' +
            '</div>';
            // 当前若在单聊中，展示与对方的亲密度
            if (App.current && App.current.type === 'USER' && App.current.id !== App.user.id) {
                html += '<div id="pt-intimacy" class="pt-intimacy">与当前好友亲密度：加载中…</div>';
            }
            html += '<div class="pt-log-title">最近积分变动</div><div class="pt-log">';
            if (log.length === 0) {
                html += '<div class="empty-tip">暂无积分变动</div>';
            } else {
                html += log.map(function (l) {
                    var sign = (l.changeVal >= 0 ? '+' : '') + l.changeVal;
                    var cls = l.changeVal >= 0 ? 'gain' : 'cost';
                    return '<div class="pt-log-item"><span class="pt-log-reason">' + App.escapeHtml(l.reason || '') +
                        '</span><span class="pt-log-val ' + cls + '">' + sign + '</span></div>';
                }).join('');
            }
            html += '</div>';
            box.innerHTML = html;
            // 单独加载亲密度（需要对方 id）
            if (App.current && App.current.type === 'USER' && App.current.id !== App.user.id) {
                Api.intimacy(App.current.id).then(function (d) {
                    var elI = document.getElementById('pt-intimacy');
                    if (elI && d && d.code === 0 && d.data) {
                        elI.textContent = '与当前好友亲密度：Lv.' + d.data.level + '（' + d.data.exp + ' exp）';
                    } else if (elI) {
                        elI.textContent = '与当前好友暂无亲密度记录';
                    }
                }).catch(function () {
                    var elI = document.getElementById('pt-intimacy');
                    if (elI) elI.textContent = '与当前好友暂无亲密度记录';
                });
            }
        });
    }

    function render() {
        var root = document.getElementById('program');
        if (!root) return;
        var cards = modules.map(function (m) {
            return '<div class="pg-card" data-key="' + m.key + '" style="--pg-c:' + m.color + '">' +
                '<div class="pg-card-icon">' + m.icon + '</div>' +
                '<div class="pg-card-name">' + App.escapeHtml(m.name) + '</div>' +
                '<div class="pg-card-desc">' + App.escapeHtml(m.desc) + '</div>' +
                '<div class="pg-card-go">进入 ›</div>' +
            '</div>';
        }).join('');

        root.innerHTML =
            '<div class="pg-head">' +
                '<div class="pg-title">程序空间</div>' +
                '<div class="pg-sub">把常用工具集中在这里，随用随取</div>' +
            '</div>' +
            '<div class="pg-grid">' + cards + '</div>' +
            '<div class="pg-foot">已上线 ' + modules.length + ' 个功能模块</div>';

        root.querySelectorAll('.pg-card').forEach(function (card) {
            var key = card.getAttribute('data-key');
            var m = modules.find(function (x) { return x.key === key; });
            if (m) card.onclick = function () { m.open(); highlight(key); };
        });

        // 桌面端：在左侧栏渲染程序空间导航（取代残留的群聊/会话列表）
        renderSidebar();
    }

    /** 左侧栏渲染程序空间导航（桌面端）。点击切换右侧 #program 内容并高亮当前项。 */
    function renderSidebar() {
        var side = document.getElementById('side-list');
        if (!side) return;
        // 移动端不显示侧栏（程序空间用卡片网格入口），跳过
        if (window.App && App.isMobile && App.isMobile()) return;
        var items = modules.map(function (m) {
            return '<div class="pg-nav-item" data-key="' + m.key + '">' +
                '<span class="pg-nav-ico">' + m.icon + '</span>' +
                '<span class="pg-nav-name">' + App.escapeHtml(m.name) + '</span>' +
                '<span class="pg-nav-go">›</span>' +
            '</div>';
        }).join('');
        side.innerHTML =
            '<div class="pg-nav-head">程序空间</div>' +
            '<div class="pg-nav">' + items + '</div>';
        side.classList.add('pg-side');
        bindSidebar();
    }

    function bindSidebar() {
        var side = document.getElementById('side-list');
        if (!side) return;
        side.querySelectorAll('.pg-nav-item').forEach(function (el) {
            var key = el.getAttribute('data-key');
            var m = modules.find(function (x) { return x.key === key; });
            if (!m) return;
            el.onclick = function () {
                m.open();
                highlight(key);
            };
        });
    }

    /** 高亮左侧导航中对应项（子模块激活时调用） */
    function highlight(key) {
        var side = document.getElementById('side-list');
        if (!side) return;
        side.querySelectorAll('.pg-nav-item').forEach(function (el) {
            el.classList.toggle('active', el.getAttribute('data-key') === key);
        });
    }

    /** 离开程序空间时清掉左侧导航（仅当它是程序空间导航时，避免误清其它列表） */
    function clearSidebar() {
        var side = document.getElementById('side-list');
        if (!side) return;
        if (side.classList.contains('pg-side')) {
            side.innerHTML = '';
            side.classList.remove('pg-side');
        }
    }

    return {
        render: render,
        modules: modules,
        highlight: highlight,
        clearSidebar: clearSidebar,
        // ① 时间胶囊
        openCapsule: openCapsule,
        createCapsule: createCapsule,
        loadCapsules: loadCapsules,
        // ⑪ 积分与亲密度
        openPoints: openPoints,
        renderPoints: renderPoints
    };
})();
