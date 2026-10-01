/* ==========================================================================
 * 家庭树 / 家族族谱
 * 作为「程序空间」的子模块，由 Family.open() 在 #program 容器内渲染。
 *
 * 五个页面：
 *   1) 我的家族列表      renderList()
 *   2) 家谱树主画布       openTree() —— AntV G6 渲染，后端算好坐标
 *   3) 成员详情抽屉       openMemberDrawer()
 *   4) 家族大事记         openTimeline()
 *   5) 家族设置弹窗       openSettings()
 *
 * 依赖：window.G6（AntV G6 4.x，由 index.html 通过 CDN 引入）
 * ========================================================================== */
window.Family = (function () {

    // 与后端 FamilyTreeLayout 保持一致（改一处必须同步另一处）
    var NODE_W = 132;
    var NODE_H = 76;

    var COLOR = {
        maleBg: '#EAF1EC',
        femaleBg: '#F8EEE4',
        unknownBg: '#F2F0EA',
        aliveBorder: '#3E5C4B',
        deadBorder: '#A09282',
        ink: '#2F2A24',
        ink2: '#7A7268',
        parent: '#6B7F6E',
        spouse: '#A9714B',
        ex: '#B8B0A4',
        highlight: '#C9973F'
    };

    var state = {
        view: 'list',          // list | tree | timeline
        families: [],
        family: null,          // 当前家族基础信息
        tree: null,            // 后端返回的树数据
        graph: null,           // G6 实例
        direction: 'TB',
        expanded: {},          // 已手动展开的节点 memberId
        readonly: false,
        shareToken: null,      // 分享模式下的 token（非 null 表示只读分享视图）
        detail: null,          // 当前抽屉中的成员详情
        events: [],
        photos: [],
        lbIndex: 0             // 大图预览下标
    };

    // ======================================================================
    // API
    // ======================================================================

    function req(method, url, body) {
        var opt = { method: method, headers: { 'Content-Type': 'application/json' } };
        if (App.token) opt.headers['X-Token'] = App.token;
        if (body !== undefined) opt.body = JSON.stringify(body);
        return fetch(url, opt).then(function (r) {
            return r.text().then(function (t) {
                try { return JSON.parse(t); } catch (e) { return { code: -1, message: t }; }
            });
        });
    }

    function q(params) {
        var parts = [];
        Object.keys(params || {}).forEach(function (k) {
            var v = params[k];
            if (v === undefined || v === null || v === '') return;
            parts.push(k + '=' + encodeURIComponent(v));
        });
        return parts.length ? ('?' + parts.join('&')) : '';
    }

    /** 统一处理后端 Result：code!==0 时抛错 */
    function unwrap(d) {
        if (!d) throw new Error('请求失败');
        if (d.code === 0) return d.data;
        throw new Error(d.message || d.msg || '请求失败');
    }

    var API = {
        list: function () { return req('GET', '/api/family/list').then(unwrap); },
        detail: function (id) { return req('GET', '/api/family/' + id).then(unwrap); },
        create: function (body) { return req('POST', '/api/family/create', body).then(unwrap); },
        update: function (body) { return req('POST', '/api/family/update', body).then(unwrap); },
        remove: function (id) { return req('DELETE', '/api/family/' + id).then(unwrap); },
        tree: function (id, direction, maxDepth, expanded, token) {
            var url = token
                ? '/api/family/share/' + token + '/tree'
                : '/api/family/' + id + '/tree';
            return req('GET', url + q({
                direction: direction, maxDepth: maxDepth,
                expanded: expanded && expanded.length ? expanded.join(',') : ''
            })).then(unwrap);
        },
        shareInfo: function (token) { return req('GET', '/api/family/share/' + token).then(unwrap); },
        genShare: function (id) { return req('POST', '/api/family/' + id + '/share').then(unwrap); },
        revokeShare: function (id) { return req('DELETE', '/api/family/' + id + '/share').then(unwrap); },
        memberDetail: function (id) { return req('GET', '/api/family/member/' + id).then(unwrap); },
        memberSave: function (body) { return req('POST', '/api/family/member/save', body).then(unwrap); },
        memberDelete: function (id) { return req('DELETE', '/api/family/member/' + id).then(unwrap); },
        deletePreview: function (id) { return req('GET', '/api/family/member/' + id + '/delete-preview').then(unwrap); },
        photos: function (mid) { return req('GET', '/api/family/member/' + mid + '/photos').then(unwrap); },
        photoDelete: function (pid) { return req('DELETE', '/api/family/photo/' + pid).then(unwrap); },
        relationAdd: function (body) { return req('POST', '/api/family/relation/add', body).then(unwrap); },
        relationRemove: function (aId, bId) {
            return req('DELETE', '/api/family/relation' + q({ aId: aId, bId: bId })).then(unwrap);
        },
        relations: function (mid) { return req('GET', '/api/family/member/' + mid + '/relations').then(unwrap); },
        events: function (fid) { return req('GET', '/api/family/' + fid + '/events').then(unwrap); },
        eventSave: function (body) { return req('POST', '/api/family/event/save', body).then(unwrap); },
        eventDelete: function (id) { return req('DELETE', '/api/family/event/' + id).then(unwrap); },
        search: function (fid, keyword) {
            return req('GET', '/api/family/' + fid + '/search' + q({ keyword: keyword })).then(unwrap);
        }
    };

    /** 带 Token 的上传（FormData） */
    function upload(url, formData) {
        var opt = { method: 'POST', body: formData };
        if (App.token) opt.headers = { 'X-Token': App.token };
        return fetch(url, opt).then(function (r) { return r.json(); });
    }

    /** 带 Token 的二进制下载（导出长图 / PDF） */
    function download(url, filename) {
        var opt = { method: 'GET' };
        if (App.token) opt.headers = { 'X-Token': App.token };
        return fetch(url, opt).then(function (r) { return r.blob(); }).then(function (blob) {
            var a = document.createElement('a');
            a.href = URL.createObjectURL(blob);
            a.download = filename;
            document.body.appendChild(a);
            a.click();
            setTimeout(function () { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
        });
    }

    // ======================================================================
    // 通用 UI 工具
    // ======================================================================

    function esc(s) {
        return App.escapeHtml ? App.escapeHtml(String(s === undefined || s === null ? '' : s))
            : String(s === undefined || s === null ? '' : s);
    }

    function notify(msg) {
        if (App.notify) App.notify(msg);
        else if (App.toast) App.toast(msg, true);
    }

    function root() {
        return document.getElementById('program');
    }

    /** 打开自定义弹窗：返回遮罩元素 */
    function modal(innerHtml, onMount) {
        var mask = document.createElement('div');
        mask.className = 'fm-modal-mask';
        mask.innerHTML = '<div class="fm-modal">' + innerHtml + '</div>';
        mask.addEventListener('click', function (e) {
            if (e.target === mask) mask.remove();
        });
        document.body.appendChild(mask);
        if (onMount) onMount(mask);
        return mask;
    }

    function confirmBox(title, html, okText, onOk) {
        var mask = modal(
            '<div class="fm-modal-title">' + esc(title) + '</div>' + html +
            '<div class="fm-modal-foot">' +
                '<button class="fm-btn ghost" data-act="cancel">取消</button>' +
                '<button class="fm-btn danger" data-act="ok">' + esc(okText) + '</button>' +
            '</div>');
        mask.querySelector('[data-act="cancel"]').onclick = function () { mask.remove(); };
        mask.querySelector('[data-act="ok"]').onclick = function () {
            var btn = this;
            btn.disabled = true;
            Promise.resolve(onOk()).then(function () { mask.remove(); })
                .catch(function (e) { notify(e.message || '操作失败'); btn.disabled = false; });
        };
        return mask;
    }

    function val(id) {
        var el = document.getElementById(id);
        return el ? (el.value || '').trim() : '';
    }

    function avatarHtml(m, cls) {
        var url = (m && m.avatarUrl) || (m && m.avatar) || '';
        var name = (m && (m.name || m.memberName)) || '';
        if (url) return '<img class="' + cls + '" src="' + esc(url) + '" alt="" onerror="this.style.display=\'none\'">';
        return '<div class="' + cls + '">' + esc(name.charAt(0) || '?') + '</div>';
    }

    function fmtDate(s) {
        return s ? String(s) : '';
    }

    // ======================================================================
    // 页面 1：我的家族列表
    // ======================================================================

    function open() {
        var r = root();
        if (!r) return;
        r.style.overflowX = 'auto';
        state.view = 'list';
        state.shareToken = null;
        if (Program && Program.highlight) Program.highlight('family');
        renderList();
    }

    function renderList() {
        var r = root();
        if (!r) return;
        destroyGraph();
        r.innerHTML =
            '<div class="fm-wrap">' +
                '<div class="fm-head">' +
                    '<div>' +
                        '<div class="fm-title">我的家族</div>' +
                        '<div class="fm-sub">记录家族脉络，传承家族记忆</div>' +
                    '</div>' +
                    '<button class="fm-btn" id="fm-create">＋ 新建家族</button>' +
                '</div>' +
                '<div id="fm-list" class="fm-grid"><div class="fm-empty">加载中…</div></div>' +
            '</div>';
        var btn = document.getElementById('fm-create');
        if (btn) btn.onclick = openCreateModal;
        loadList();
    }

    function loadList() {
        API.list().then(function (list) {
            state.families = list || [];
            var box = document.getElementById('fm-list');
            if (!box) return;
            if (!state.families.length) {
                box.innerHTML = '<div class="fm-empty" style="grid-column:1/-1">' +
                    '还没有家族，点击右上角「新建家族」开始记录你的家族故事 🌳</div>';
                return;
            }
            box.innerHTML = state.families.map(function (f) {
                var cover = f.coverUrl
                    ? '<img src="' + esc(f.coverUrl) + '" alt="">'
                    : '🌳';
                var tag = f.visibility === 2 ? '<span class="fm-tag public">公开</span>'
                    : (f.visibility === 1 ? '<span class="fm-tag">链接可见</span>'
                        : '<span class="fm-tag">私有</span>');
                return '<div class="fm-fam-card" data-id="' + f.id + '">' +
                    '<div class="fm-fam-cover">' + cover + '</div>' +
                    '<div class="fm-fam-body">' +
                        '<div class="fm-fam-name">' + esc(f.name) + '</div>' +
                        '<div class="fm-fam-intro">' + esc(f.intro || '暂无简介') + '</div>' +
                        '<div class="fm-fam-meta"><span>' + (f.memberCount || 0) + ' 位成员</span>' + tag + '</div>' +
                    '</div>' +
                '</div>';
            }).join('');
            box.querySelectorAll('.fm-fam-card').forEach(function (card) {
                card.onclick = function () { openTree(Number(card.getAttribute('data-id'))); };
            });
        }).catch(function (e) {
            var box = document.getElementById('fm-list');
            if (box) box.innerHTML = '<div class="fm-empty">加载失败：' + esc(e.message) + '</div>';
        });
    }

    /** 新建家族弹窗 */
    function openCreateModal() {
        var mask = modal(
            '<div class="fm-modal-title">新建家族</div>' +
            '<div class="fm-field" style="margin-bottom:10px">' +
                '<label>家族名称 *</label>' +
                '<input id="fm-f-name" class="fm-input" maxlength="64" placeholder="如：颍川陈氏">' +
            '</div>' +
            '<div class="fm-field" style="margin-bottom:10px">' +
                '<label>家族简介</label>' +
                '<textarea id="fm-f-intro" class="fm-textarea" maxlength="500" placeholder="家族渊源、字辈、迁徙史…"></textarea>' +
            '</div>' +
            '<div class="fm-field" style="margin-bottom:10px">' +
                '<label>可见性</label>' +
                '<select id="fm-f-vis" class="fm-select">' +
                    '<option value="0">私有（仅自己与家族成员可见）</option>' +
                    '<option value="1">链接只读（凭分享链接查看）</option>' +
                    '<option value="2">公开（所有登录用户可见）</option>' +
                '</select>' +
            '</div>' +
            '<div class="fm-field" style="margin-bottom:6px">' +
                '<label>封面图（可选）</label>' +
                '<input type="file" id="fm-f-cover" accept="image/*">' +
            '</div>' +
            '<div class="fm-modal-foot">' +
                '<button class="fm-btn ghost" data-act="cancel">取消</button>' +
                '<button class="fm-btn" data-act="ok">创建</button>' +
            '</div>');
        mask.querySelector('[data-act="cancel"]').onclick = function () { mask.remove(); };
        mask.querySelector('[data-act="ok"]').onclick = function () {
            var name = val('fm-f-name');
            if (!name) { notify('请填写家族名称'); return; }
            var btn = this;
            btn.disabled = true;
            var fileEl = document.getElementById('fm-f-cover');
            var afterUpload = function (url) {
                API.create({
                    name: name,
                    intro: val('fm-f-intro'),
                    coverUrl: url || '',
                    visibility: Number(val('fm-f-vis') || 0)
                }).then(function () {
                    notify('🌳 家族已创建');
                    mask.remove();
                    loadList();
                }).catch(function (e) {
                    notify(e.message || '创建失败');
                    btn.disabled = false;
                });
            };
            if (fileEl && fileEl.files && fileEl.files[0]) {
                var fd = new FormData();
                fd.append('file', fileEl.files[0]);
                fd.append('prefix', 'family/cover');
                upload('/api/file/upload', fd).then(function (d) {
                    afterUpload(d && d.code === 0 && d.data ? d.data.url : '');
                }).catch(function () { afterUpload(''); });
            } else {
                afterUpload('');
            }
        };
    }

    // ======================================================================
    // 页面 2：家谱树主画布
    // ======================================================================

    function openTree(familyId) {
        state.view = 'tree';
        state.family = { id: familyId };
        state.expanded = {};
        state.shareToken = null;
        renderCanvasShell();
        API.detail(familyId).then(function (f) {
            state.family = f;
            state.readonly = !f.writable;
            renderToolbar();
        }).catch(function (e) {
            notify(e.message || '加载家族失败');
        });
        loadTree();
    }

    /** 只读分享入口（URL hash：#/family/share/<token>） */
    function openShare(token) {
        if (App.switchTab) App.switchTab('program');
        API.shareInfo(token).then(function (info) {
            state.shareToken = token;
            state.view = 'tree';
            state.family = { id: info.id, name: info.name, writable: false, memberCount: info.memberCount };
            state.readonly = true;
            state.expanded = {};
            renderCanvasShell();
            renderToolbar();
            loadTree();
        }).catch(function (e) {
            notify('分享链接无效或已失效');
        });
    }

    function renderCanvasShell() {
        var r = root();
        if (!r) return;
        r.innerHTML =
            '<div class="fm-wrap fm-canvas-view">' +
                '<div id="fm-toolbar" class="fm-toolbar"></div>' +
                '<div id="fm-canvas-box" class="fm-canvas-box">' +
                    '<div id="fm-canvas" class="fm-canvas"></div>' +
                    '<div id="fm-tip" class="fm-tooltip"></div>' +
                    '<div class="fm-legend">' +
                        '<div><i style="background:' + COLOR.maleBg + ';border:1px solid ' + COLOR.aliveBorder + '"></i>男 · 在世</div>' +
                        '<div><i style="background:' + COLOR.femaleBg + ';border:1px solid ' + COLOR.aliveBorder + '"></i>女 · 在世</div>' +
                        '<div><i style="background:' + COLOR.unknownBg + ';border:1px solid ' + COLOR.deadBorder + '"></i>已故</div>' +
                    '</div>' +
                    '<div class="fm-zoom">' +
                        '<button id="fm-zoom-out">－</button>' +
                        '<span id="fm-zoom-val">100%</span>' +
                        '<button id="fm-zoom-in">＋</button>' +
                        '<button id="fm-fit">居中</button>' +
                    '</div>' +
                '</div>' +
            '</div>';
        var out = document.getElementById('fm-zoom-out');
        var inn = document.getElementById('fm-zoom-in');
        var fit = document.getElementById('fm-fit');
        if (out) out.onclick = function () { if (state.graph) state.graph.zoom(0.85, { x: 0, y: 0 }); updateZoom(); };
        if (inn) inn.onclick = function () { if (state.graph) state.graph.zoom(1.18, { x: 0, y: 0 }); updateZoom(); };
        if (fit) fit.onclick = function () { if (state.graph) { state.graph.fitCenter(); updateZoom(); } };
        renderToolbar();
    }

    function renderToolbar() {
        var tb = document.getElementById('fm-toolbar');
        if (!tb) return;
        var f = state.family || {};
        var ro = state.readonly;
        tb.innerHTML =
            '<button class="fm-tb-btn" data-act="back">‹ 家族列表</button>' +
            '<div class="fm-tb-sep"></div>' +
            '<div class="fm-tb-title">' + esc(f.name || '家族族谱') + '</div>' +
            '<div class="fm-tb-sep"></div>' +
            '<button class="fm-tb-btn' + (state.direction === 'TB' ? ' active' : '') + '" data-act="dir-tb">纵向</button>' +
            '<button class="fm-tb-btn' + (state.direction === 'LR' ? ' active' : '') + '" data-act="dir-lr">横向</button>' +
            '<div class="fm-tb-sep"></div>' +
            '<button class="fm-tb-btn" data-act="add-member">＋ 成员</button>' +
            '<button class="fm-tb-btn" data-act="timeline">📜 大事记</button>' +
            '<button class="fm-tb-btn" data-act="settings">⚙ 设置</button>' +
            '<div class="fm-tb-sep"></div>' +
            '<button class="fm-tb-btn" data-act="png">导出图片</button>' +
            '<button class="fm-tb-btn" data-act="pdf">导出 PDF</button>' +
            (ro ? '' : '<button class="fm-tb-btn" data-act="share">🔗 分享</button>') +
            '<div class="fm-search-box">' +
                '<input id="fm-search-input" placeholder="搜索姓名，回车定位">' +
                '<div id="fm-search-result" class="fm-search-result" style="display:none"></div>' +
            '</div>';
        tb.querySelectorAll('.fm-tb-btn').forEach(function (b) {
            b.onclick = function () { onToolbar(b.getAttribute('data-act')); };
        });
        var input = document.getElementById('fm-search-input');
        if (input) {
            input.oninput = function () { doSearch(input.value); };
            input.onblur = function () {
                setTimeout(function () {
                    var box = document.getElementById('fm-search-result');
                    if (box) box.style.display = 'none';
                }, 200);
            };
        }
        if (ro) {
            var box = document.getElementById('fm-canvas-box');
            if (box && !document.getElementById('fm-ro-tip')) {
                var tip = document.createElement('div');
                tip.id = 'fm-ro-tip';
                tip.className = 'fm-readonly';
                tip.style.cssText = 'position:absolute;top:10px;left:12px;z-index:20';
                tip.textContent = '只读模式：你有查看权限，但不能编辑';
                box.appendChild(tip);
            }
        }
    }

    function onToolbar(act) {
        var fid = state.family && state.family.id;
        switch (act) {
            case 'back': open(); break;
            case 'dir-tb': state.direction = 'TB'; renderToolbar(); loadTree(); break;
            case 'dir-lr': state.direction = 'LR'; renderToolbar(); loadTree(); break;
            case 'add-member': openMemberForm(null); break;
            case 'timeline': openTimeline(); break;
            case 'settings': openSettings(); break;
            case 'png':
                download('/api/family/' + fid + '/export/png' + q({ direction: state.direction }),
                    'family-tree-' + fid + '.png').then(function () { notify('长图已导出'); })
                    .catch(function () { notify('导出失败'); });
                break;
            case 'pdf':
                download('/api/family/' + fid + '/export/pdf' + q({ direction: state.direction }),
                    'family-tree-' + fid + '.pdf').then(function () { notify('PDF 已导出'); })
                    .catch(function () { notify('导出失败'); });
                break;
            case 'share': openShareDialog(); break;
        }
    }

    function updateZoom() {
        var el = document.getElementById('fm-zoom-val');
        if (el && state.graph) el.textContent = Math.round(state.graph.getZoom() * 100) + '%';
    }

    /** 搜索：命中后高亮并平移定位 */
    function doSearch(keyword) {
        var box = document.getElementById('fm-search-result');
        if (!box) return;
        if (!keyword || !keyword.trim()) { box.style.display = 'none'; return; }
        API.search(state.family.id, keyword).then(function (list) {
            if (!list || !list.length) {
                box.innerHTML = '<div class="fm-search-item">未找到匹配成员</div>';
                box.style.display = 'block';
                return;
            }
            box.innerHTML = list.map(function (m) {
                return '<div class="fm-search-item" data-id="' + m.memberId + '">🔍 ' + esc(m.name) + '</div>';
            }).join('');
            box.style.display = 'block';
            box.querySelectorAll('.fm-search-item').forEach(function (it) {
                it.onclick = function () {
                    focusMember(Number(it.getAttribute('data-id')));
                    box.style.display = 'none';
                };
            });
        }).catch(function () { box.style.display = 'none'; });
    }

    function focusMember(memberId) {
        if (!state.graph) return;
        var item = state.graph.findById(String(memberId));
        if (!item) { notify('该成员在当前视图中未展开'); return; }
        state.graph.getNodes().forEach(function (n) { state.graph.setItemState(n, 'highlight', false); });
        state.graph.setItemState(item, 'highlight', true);
        state.graph.focusItem(item, true);
        updateZoom();
    }

    // ---------- 树数据加载与渲染 ----------

    function loadTree() {
        var fid = state.family && state.family.id;
        if (!fid) return;
        var expandedIds = Object.keys(state.expanded).filter(function (k) { return state.expanded[k]; });
        API.tree(fid, state.direction, 0, expandedIds, state.shareToken).then(function (vo) {
            state.tree = vo;
            state.readonly = !!vo.readonly;
            renderTree(vo);
            renderToolbar();
        }).catch(function (e) {
            notify(e.message || '加载族谱失败');
        });
    }

    function destroyGraph() {
        if (state.graph) {
            try { state.graph.destroy(); } catch (e) { /* 忽略销毁异常 */ }
            state.graph = null;
        }
    }

    function renderTree(vo) {
        var container = document.getElementById('fm-canvas');
        if (!container) return;
        if (typeof G6 === 'undefined') {
            container.innerHTML = '<div style="padding:40px;text-align:center;color:#7A7268">' +
                '家谱渲染库（AntV G6）未加载，请检查网络后刷新页面</div>';
            return;
        }
        registerShapes();
        var box = document.getElementById('fm-canvas-box');
        var width = box ? box.clientWidth : 1200;
        var height = box ? box.clientHeight : 600;

        var data = {
            nodes: (vo.nodes || []).map(function (n) {
                return {
                    id: n.id,
                    x: n.x,
                    y: n.y,
                    memberId: n.memberId,
                    name: n.name,
                    avatar: n.avatar,
                    gender: n.gender,
                    alive: n.alive,
                    birthYear: n.birthYear,
                    deathYear: n.deathYear,
                    bioBrief: n.bioBrief,
                    collapsed: n.collapsed,
                    foldedDescendants: n.foldedDescendants,
                    generation: n.generation
                };
            }),
            edges: (vo.edges || []).map(function (e, i) {
                var spouse = e.type === 'SPOUSE' || e.type === 'EX_SPOUSE';
                var dashed = e.type === 'EX_SPOUSE' || e.type === 'STEP_PARENT' || e.type === 'ADOPTED';
                var color = spouse ? (e.type === 'EX_SPOUSE' ? COLOR.ex : COLOR.spouse) : COLOR.parent;
                return {
                    id: 'e' + i,
                    source: e.source,
                    target: e.target,
                    edgeKind: spouse ? 'spouse' : 'parent',
                    type: spouse ? 'line' : 'family-edge',
                    style: {
                        stroke: color,
                        lineWidth: spouse ? 2 : 1.6,
                        lineDash: dashed ? [5, 4] : null,
                        opacity: 0.9
                    }
                };
            })
        };

        if (!state.graph) {
            state.graph = new G6.Graph({
                container: container,
                width: width,
                height: height,
                fitView: false,
                minZoom: 0.15,
                maxZoom: 3,
                modes: { default: ['drag-canvas', 'zoom-canvas'] },
                defaultNode: { type: 'family-node', size: [NODE_W, NODE_H] },
                defaultEdge: { type: 'family-edge' }
            });
            bindGraphEvents();
        } else {
            state.graph.changeSize(width, height);
        }
        state.graph.data(data);
        state.graph.render();
        state.graph.fitCenter();
        updateZoom();
    }

    function bindGraphEvents() {
        var g = state.graph;
        g.on('node:click', function (e) {
            var model = e.item && e.item.getModel();
            if (model && model.memberId) openMemberDrawer(model.memberId);
        });
        g.on('node:mouseenter', function (e) {
            var m = e.item && e.item.getModel();
            if (!m) return;
            g.setItemState(e.item, 'hover', true);
            showTip(m, e);
        });
        g.on('node:mouseleave', function (e) {
            if (e.item) g.setItemState(e.item, 'hover', false);
            hideTip();
        });
        g.on('node:contextmenu', function (e) {
            e.preventDefault && e.preventDefault();
            var m = e.item && e.item.getModel();
            if (m) showContextMenu(m, e);
        });
        g.on('canvas:click', function () { hideContextMenu(); hideTip(); });
        g.on('canvas:contextmenu', function (e) {
            if (e.preventDefault) e.preventDefault();
            hideContextMenu();
        });
        g.on('wheelzoom', updateZoom);
        g.on('viewportchange', updateZoom);
        // 窗口尺寸变化时重排画布
        window.addEventListener('resize', function () {
            if (!state.graph || state.view !== 'tree') return;
            var box = document.getElementById('fm-canvas-box');
            if (!box) return;
            state.graph.changeSize(box.clientWidth, box.clientHeight);
        });
    }

    // ---------- G6 自定义节点 / 边 ----------

    var shapesReady = false;

    function registerShapes() {
        if (shapesReady || typeof G6 === 'undefined') return;

        G6.registerNode('family-node', {
            draw: function (cfg, group) {
                var w = NODE_W;
                var h = NODE_H;
                var gender = cfg.gender || 0;
                var fill = gender === 2 ? COLOR.femaleBg : (gender === 1 ? COLOR.maleBg : COLOR.unknownBg);
                var stroke = cfg.alive === false ? COLOR.deadBorder : COLOR.aliveBorder;

                var card = group.addShape('rect', {
                    attrs: {
                        x: -w / 2, y: -h / 2, width: w, height: h, radius: 10,
                        fill: fill, stroke: stroke, lineWidth: 1.6, cursor: 'pointer'
                    },
                    name: 'card',
                    draggable: true
                });

                var avX = -w / 2 + 8;
                var avSize = 40;
                var avY = -avSize / 2;
                if (cfg.avatar) {
                    group.addShape('image', {
                        attrs: {
                            x: avX, y: avY, width: avSize, height: avSize,
                            img: cfg.avatar, radius: avSize / 2
                        },
                        name: 'avatar',
                        draggable: true
                    });
                    group.addShape('circle', {
                        attrs: {
                            x: avX + avSize / 2, y: 0, r: avSize / 2,
                            stroke: stroke, lineWidth: 1.2, fill: 'transparent'
                        },
                        name: 'avatar-ring'
                    });
                } else {
                    group.addShape('circle', {
                        attrs: {
                            x: avX + avSize / 2, y: 0, r: avSize / 2,
                            fill: gender === 2 ? '#E8C9B0' : (gender === 1 ? '#C3D3C6' : '#E3DFD6'),
                            stroke: stroke, lineWidth: 1.2
                        },
                        name: 'avatar-bg',
                        draggable: true
                    });
                    group.addShape('text', {
                        attrs: {
                            x: avX + avSize / 2, y: 0,
                            text: (cfg.name || '?').charAt(0),
                            fontSize: 18, fill: COLOR.ink,
                            textAlign: 'center', textBaseline: 'middle'
                        },
                        name: 'avatar-text'
                    });
                }

                // 姓名
                group.addShape('text', {
                    attrs: {
                        x: avX + avSize + 10, y: -12,
                        text: clip(cfg.name || '未命名', 7),
                        fontSize: 14, fontWeight: 600, fill: COLOR.ink,
                        textBaseline: 'middle'
                    },
                    name: 'name',
                    draggable: true
                });
                // 生卒年
                group.addShape('text', {
                    attrs: {
                        x: avX + avSize + 10, y: 9,
                        text: yearText(cfg),
                        fontSize: 12, fill: COLOR.ink2, textBaseline: 'middle'
                    },
                    name: 'years'
                });

                // 折叠徽标：+N（点击展开后代）
                if (cfg.collapsed && cfg.foldedDescendants > 0) {
                    group.addShape('circle', {
                        attrs: {
                            x: w / 2 - 4, y: h / 2 - 2, r: 11,
                            fill: COLOR.spouse, stroke: '#fff', lineWidth: 1.5, cursor: 'pointer'
                        },
                        name: 'badge'
                    });
                    group.addShape('text', {
                        attrs: {
                            x: w / 2 - 4, y: h / 2 - 2,
                            text: cfg.foldedDescendants > 99 ? '99+' : ('+' + cfg.foldedDescendants),
                            fontSize: 9, fill: '#fff', textAlign: 'center', textBaseline: 'middle'
                        },
                        name: 'badge-text'
                    });
                }
                return card;
            },
            setState: function (name, value, item) {
                var group = item.getContainer();
                var card = group.find(function (e) { return e.get('name') === 'card'; });
                if (!card) return;
                var model = item.getModel();
                var base = model.alive === false ? COLOR.deadBorder : COLOR.aliveBorder;
                if (name === 'hover') {
                    card.attr('lineWidth', value ? 2.6 : 1.6);
                    card.attr('shadowColor', value ? 'rgba(62,92,75,.35)' : null);
                    card.attr('shadowBlur', value ? 12 : 0);
                } else if (name === 'highlight') {
                    card.attr('stroke', value ? COLOR.highlight : base);
                    card.attr('lineWidth', value ? 3 : 1.6);
                    card.attr('shadowColor', value ? 'rgba(201,151,63,.5)' : null);
                    card.attr('shadowBlur', value ? 16 : 0);
                }
            }
        }, 'single-node');

        /** 父子连线：正交折线（与后端导出保持一致的画法） */
        G6.registerEdge('family-edge', {
            draw: function (cfg, group) {
                var s = cfg.startPoint;
                var t = cfg.endPoint;
                var path;
                if (state.direction === 'LR') {
                    var midX = (s.x + t.x) / 2;
                    path = [['M', s.x, s.y], ['L', midX, s.y], ['L', midX, t.y], ['L', t.x, t.y]];
                } else {
                    var midY = (s.y + t.y) / 2;
                    path = [['M', s.x, s.y], ['L', s.x, midY], ['L', t.x, midY], ['L', t.x, t.y]];
                }
                return group.addShape('path', {
                    attrs: { path: path, stroke: COLOR.parent, lineWidth: 1.6, lineAppendWidth: 8 },
                    name: 'edge-path'
                });
            },
            setState: function (name, value, item) {
                var group = item.getContainer();
                var p = group.find(function (e) { return e.get('name') === 'edge-path'; });
                if (!p) return;
                if (name === 'highlight') {
                    p.attr('lineWidth', value ? 3 : 1.6);
                    p.attr('stroke', value ? COLOR.highlight : COLOR.parent);
                }
            }
        }, 'line');

        shapesReady = true;
    }

    function yearText(cfg) {
        var b = cfg.birthYear || '';
        var d = cfg.deathYear || '';
        if (!b && !d) return '生卒不详';
        if (!d) return b + ' –';      // 在世：只标出生年，避免节点内截断
        return b + ' – ' + d;
    }

    function clip(s, max) {
        if (!s) return '';
        return s.length > max ? s.substring(0, max) + '…' : s;
    }

    // ---------- tooltip ----------

    function showTip(m, e) {
        var tip = document.getElementById('fm-tip');
        var box = document.getElementById('fm-canvas-box');
        if (!tip || !box) return;
        var lines = ['<b>' + esc(m.name) + '</b>'];
        lines.push(yearText(m));
        if (m.bioBrief) lines.push(esc(m.bioBrief));
        if (m.collapsed && m.foldedDescendants > 0) lines.push('（点击 + 展开 ' + m.foldedDescendants + ' 位后代）');
        tip.innerHTML = lines.join('<br>');
        tip.classList.add('show');
        var rect = box.getBoundingClientRect();
        var x = (e.clientX || 0) - rect.left + 14;
        var y = (e.clientY || 0) - rect.top + 14;
        tip.style.left = Math.min(x, rect.width - 270) + 'px';
        tip.style.top = Math.min(y, rect.height - 90) + 'px';
    }

    function hideTip() {
        var tip = document.getElementById('fm-tip');
        if (tip) tip.classList.remove('show');
    }

    // ---------- 右键菜单 ----------

    function showContextMenu(m, e) {
        hideContextMenu();
        var box = document.getElementById('fm-canvas-box');
        if (!box) return;
        var menu = document.createElement('div');
        menu.className = 'fm-ctx';
        menu.id = 'fm-ctx-menu';
        var items = state.readonly
            ? [['查看资料', 'view'], ['以此为中心', 'center']]
            : [
                ['添加配偶', 'spouse'],
                ['添加子女', 'child'],
                ['添加父母', 'parent'],
                ['sep', ''],
                ['编辑信息', 'edit'],
                ['上传照片', 'photo'],
                ['sep', ''],
                ['添加养子女', 'adopted'],
                ['添加继父母', 'step'],
                ['sep', ''],
                ['删除成员', 'delete']
            ];
        menu.innerHTML = items.map(function (it) {
            if (it[0] === 'sep') return '<div class="fm-ctx-sep"></div>';
            var cls = it[1] === 'delete' ? 'fm-ctx-item danger' : 'fm-ctx-item';
            return '<div class="' + cls + '" data-act="' + it[1] + '">' + it[0] + '</div>';
        }).join('');
        box.appendChild(menu);
        var rect = box.getBoundingClientRect();
        var px = (e.clientX || 0) - rect.left;
        var py = (e.clientY || 0) - rect.top;
        menu.style.left = Math.min(px, rect.width - 160) + 'px';
        menu.style.top = Math.min(py, rect.height - items.length * 30 - 10) + 'px';
        menu.querySelectorAll('.fm-ctx-item').forEach(function (el) {
            el.onclick = function () {
                hideContextMenu();
                onMemberAction(m.memberId, el.getAttribute('data-act'));
            };
        });
    }

    function hideContextMenu() {
        var el = document.getElementById('fm-ctx-menu');
        if (el) el.remove();
    }

    function onMemberAction(memberId, act) {
        switch (act) {
            case 'view':
            case 'edit': openMemberForm(memberId); break;
            case 'center': focusMember(memberId); break;
            case 'spouse': openRelationForm(memberId, 'SPOUSE', '添加配偶'); break;
            case 'child': openRelationForm(memberId, 'CHILD', '添加子女'); break;
            case 'parent': openRelationForm(memberId, 'PARENT', '添加父母'); break;
            case 'adopted': openRelationForm(memberId, 'ADOPTED_CHILD', '添加养子女'); break;
            case 'step': openRelationForm(memberId, 'STEP_PARENT', '添加继父母'); break;
            case 'photo': openMemberDrawer(memberId, true); break;
            case 'delete': confirmDeleteMember(memberId); break;
        }
    }

    function confirmDeleteMember(memberId) {
        API.deletePreview(memberId).then(function (p) {
            var warn = '<div class="fm-warn">确定删除成员「' + esc(p.name) + '」？<br>' +
                '将同时解除其 <b>' + (p.relationCount || 0) + '</b> 条亲属关系、' +
                '清理 <b>' + (p.photoCount || 0) + '</b> 张照片。' +
                (p.descendantCount > 0
                    ? '<br>⚠ 该成员有 <b>' + p.descendantCount + '</b> 位后代（' +
                      esc((p.descendantNames || []).join('、')) + '），删除后他们将失去与此人的连线（成员本身保留）。'
                    : '') +
                '<br><b>此操作不可撤销。</b></div>';
            confirmBox('删除成员', warn, '确认删除', function () {
                return API.memberDelete(memberId).then(function () {
                    notify('已删除成员');
                    loadTree();
                });
            });
        }).catch(function (e) { notify(e.message || '预检失败'); });
    }

    // ======================================================================
    // 成员表单（新增 / 编辑）
    // ======================================================================

    function openMemberForm(memberId) {
        var isEdit = !!memberId;
        var title = isEdit ? '编辑成员资料' : '新增家族成员';
        var save = function (mask) {
            var birth = val('fm-m-birth');
            var death = val('fm-m-death');
            if (birth && death && death < birth) { notify('逝世日期不能早于出生日期'); return; }
            var body = {
                id: isEdit ? Number(memberId) : null,
                familyId: isEdit ? undefined : state.family.id,
                name: val('fm-m-name'),
                gender: Number(val('fm-m-gender') || 0),
                birthDate: birth,
                deathDate: death,
                bio: val('fm-m-bio'),
                occupation: val('fm-m-occ'),
                hometown: val('fm-m-home'),
                phone: val('fm-m-phone'),
                address: val('fm-m-addr'),
                remark: val('fm-m-remark'),
                avatarUrl: val('fm-m-avatar'),
                version: Number(val('fm-m-version') || 0)
            };
            if (!body.name) { notify('请填写姓名'); return; }
            var btn = mask.querySelector('[data-act="ok"]');
            if (btn) btn.disabled = true;
            API.memberSave(body).then(function () {
                notify(isEdit ? '资料已更新' : '成员已添加');
                mask.remove();
                loadTree();
                if (state.detail && state.detail.id === Number(memberId)) openMemberDrawer(Number(memberId));
            }).catch(function (e) {
                notify(e.message || '保存失败');
                if (btn) btn.disabled = false;
            });
        };
        var fill = function (mask, d) {
            d = d || {};
            var set = function (id, v) { var el = document.getElementById(id); if (el) el.value = v || ''; };
            set('fm-m-name', d.name);
            set('fm-m-gender', d.gender !== undefined && d.gender !== null ? d.gender : 0);
            set('fm-m-birth', fmtDate(d.birthDate));
            set('fm-m-death', fmtDate(d.deathDate));
            set('fm-m-bio', d.bio);
            set('fm-m-occ', d.occupation);
            set('fm-m-home', d.hometown);
            set('fm-m-phone', d.phone);
            set('fm-m-addr', d.address);
            set('fm-m-remark', d.remark);
            set('fm-m-avatar', d.avatarUrl);
            set('fm-m-version', d.version);
        };
        var html =
            '<div class="fm-modal-title">' + title + '</div>' +
            '<div class="fm-form-row">' +
                '<div class="fm-field"><label>姓名 *</label><input id="fm-m-name" class="fm-input" maxlength="64"></div>' +
                '<div class="fm-field"><label>性别</label><select id="fm-m-gender" class="fm-select">' +
                    '<option value="0">未知</option><option value="1">男</option><option value="2">女</option></select></div>' +
            '</div>' +
            '<div class="fm-form-row">' +
                '<div class="fm-field"><label>出生日期</label><input id="fm-m-birth" type="date" class="fm-input"></div>' +
                '<div class="fm-field"><label>逝世日期（在世请留空）</label><input id="fm-m-death" type="date" class="fm-input"></div>' +
            '</div>' +
            '<div class="fm-form-row">' +
                '<div class="fm-field"><label>职业</label><input id="fm-m-occ" class="fm-input" maxlength="128"></div>' +
                '<div class="fm-field"><label>籍贯</label><input id="fm-m-home" class="fm-input" maxlength="128"></div>' +
            '</div>' +
            '<div class="fm-form-row">' +
                '<div class="fm-field"><label>联系电话</label><input id="fm-m-phone" class="fm-input" maxlength="32"></div>' +
                '<div class="fm-field"><label>住址</label><input id="fm-m-addr" class="fm-input" maxlength="255"></div>' +
            '</div>' +
            '<div class="fm-field" style="margin-bottom:10px">' +
                '<label>人物简介（生平介绍）</label>' +
                '<textarea id="fm-m-bio" class="fm-textarea" maxlength="4000" placeholder="生平事迹、性格特点、重要经历…"></textarea>' +
            '</div>' +
            '<div class="fm-field" style="margin-bottom:10px">' +
                '<label>备注</label><input id="fm-m-remark" class="fm-input" maxlength="500">' +
            '</div>' +
            '<div class="fm-field" style="margin-bottom:6px">' +
                '<label>头像</label>' +
                '<div style="display:flex;gap:8px;align-items:center">' +
                    '<input type="file" id="fm-m-file" accept="image/*" style="flex:1">' +
                    '<button class="fm-tb-btn" id="fm-m-up">上传</button>' +
                '</div>' +
                '<input type="hidden" id="fm-m-avatar"><input type="hidden" id="fm-m-version">' +
                '<div class="fm-hint">上传后自动填入头像地址；也可直接在成员详情里拖拽上传照片。</div>' +
            '</div>' +
            '<div class="fm-modal-foot">' +
                '<button class="fm-btn ghost" data-act="cancel">取消</button>' +
                '<button class="fm-btn" data-act="ok">保存</button>' +
            '</div>';
        var mask = modal(html, function (m) {
            m.querySelector('[data-act="cancel"]').onclick = function () { m.remove(); };
            m.querySelector('[data-act="ok"]').onclick = function () { save(m); };
            var up = m.querySelector('#fm-m-up');
            if (up) up.onclick = function () {
                var f = document.getElementById('fm-m-file');
                if (!f || !f.files || !f.files[0]) { notify('请先选择图片'); return; }
                var fd = new FormData();
                fd.append('file', f.files[0]);
                fd.append('prefix', 'family/avatar');
                upload('/api/file/upload', fd).then(function (d) {
                    if (d && d.code === 0 && d.data) {
                        document.getElementById('fm-m-avatar').value = d.data.url;
                        notify('头像已上传，请点击保存');
                    } else notify('上传失败');
                }).catch(function () { notify('上传失败'); });
            };
            if (isEdit) {
                API.memberDetail(memberId).then(function (d) { fill(m, d); })
                    .catch(function () { m.remove(); });
            }
        });
        return mask;
    }

    // ======================================================================
    // 页面 3：成员详情抽屉
    // ======================================================================

    function openMemberDrawer(memberId, focusPhoto) {
        API.memberDetail(memberId).then(function (d) {
            state.detail = d;
            renderDrawer(d);
            if (focusPhoto) {
                var el = document.getElementById('fm-photo-input');
                if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' });
            }
        }).catch(function (e) { notify(e.message || '加载成员失败'); });
    }

    function renderDrawer(d) {
        var wrap = document.createElement('div');
        wrap.className = 'fm-drawer-mask';
        wrap.id = 'fm-drawer-mask';
        wrap.addEventListener('click', function (e) {
            if (e.target === wrap) { wrap.remove(); state.detail = null; }
        });

        var genderTxt = d.gender === 1 ? '男' : (d.gender === 2 ? '女' : '未知');
        var alive = !d.deathDate;
        var sub = genderTxt + ' · ' + (alive ? '在世' : '已故') +
            (d.birthDate ? ' · ' + fmtDate(d.birthDate) + ' 年生' : '');

        wrap.innerHTML =
            '<div class="fm-drawer">' +
                '<div class="fm-drawer-head">' +
                    '<div class="fm-drawer-avatar">' +
                        (d.avatarUrl ? '<img src="' + esc(d.avatarUrl) + '" style="width:100%;height:100%;border-radius:50%;object-fit:cover" alt="">'
                            : esc((d.name || '?').charAt(0))) +
                    '</div>' +
                    '<div>' +
                        '<div class="fm-drawer-name">' + esc(d.name) + '</div>' +
                        '<div class="fm-drawer-sub">' + esc(sub) + '</div>' +
                    '</div>' +
                    '<button class="fm-drawer-close" data-act="close">×</button>' +
                '</div>' +

                '<div class="fm-sec">' +
                    '<div class="fm-sec-title">基础信息 <span class="tag">' + (d.writable ? '可编辑' : '只读') + '</span></div>' +
                    '<div class="fm-form-row">' +
                        '<div class="fm-field"><label>姓名</label><input id="fm-d-name" class="fm-input" value="' + esc(d.name) + '"' + (d.writable ? '' : ' disabled') + '></div>' +
                        '<div class="fm-field"><label>性别</label><select id="fm-d-gender" class="fm-select"' + (d.writable ? '' : ' disabled') + '>' +
                            '<option value="0"' + (d.gender === 0 ? ' selected' : '') + '>未知</option>' +
                            '<option value="1"' + (d.gender === 1 ? ' selected' : '') + '>男</option>' +
                            '<option value="2"' + (d.gender === 2 ? ' selected' : '') + '>女</option></select></div>' +
                    '</div>' +
                    '<div class="fm-form-row">' +
                        '<div class="fm-field"><label>出生日期</label><input id="fm-d-birth" type="date" class="fm-input" value="' + fmtDate(d.birthDate) + '"' + (d.writable ? '' : ' disabled') + '></div>' +
                        '<div class="fm-field"><label>逝世日期</label><input id="fm-d-death" type="date" class="fm-input" value="' + fmtDate(d.deathDate) + '"' + (d.writable ? '' : ' disabled') + '></div>' +
                    '</div>' +
                    '<div class="fm-form-row">' +
                        '<div class="fm-field"><label>职业</label><input id="fm-d-occ" class="fm-input" value="' + esc(d.occupation) + '"' + (d.writable ? '' : ' disabled') + '></div>' +
                        '<div class="fm-field"><label>籍贯</label><input id="fm-d-home" class="fm-input" value="' + esc(d.hometown) + '"' + (d.writable ? '' : ' disabled') + '></div>' +
                    '</div>' +
                    '<div class="fm-form-row">' +
                        '<div class="fm-field"><label>联系电话</label><input id="fm-d-phone" class="fm-input" value="' + esc(d.phone) + '"' + (d.writable ? '' : ' disabled') + '></div>' +
                        '<div class="fm-field"><label>住址</label><input id="fm-d-addr" class="fm-input" value="' + esc(d.address) + '"' + (d.writable ? '' : ' disabled') + '></div>' +
                    '</div>' +
                    '<div class="fm-field"><label>人物简介</label>' +
                        '<textarea id="fm-d-bio" class="fm-textarea"' + (d.writable ? '' : ' disabled') + '>' + esc(d.bio) + '</textarea></div>' +
                    (d.writable ? '<div style="margin-top:10px;text-align:right">' +
                        '<button class="fm-btn" id="fm-d-save">保存修改</button></div>' : '') +
                '</div>' +

                '<div class="fm-sec">' +
                    '<div class="fm-sec-title">相册 <span class="tag">支持拖拽上传</span></div>' +
                    '<div class="fm-photo-grid" id="fm-photo-grid"></div>' +
                    (d.writable ? '<input type="file" id="fm-photo-input" accept="image/*" multiple style="display:none">' : '') +
                '</div>' +

                '<div class="fm-sec">' +
                    '<div class="fm-sec-title">关联亲属</div>' +
                    '<div class="fm-rel-list" id="fm-rel-list"></div>' +
                    (d.writable ? '<div style="margin-top:10px;display:flex;gap:8px;flex-wrap:wrap">' +
                        '<button class="fm-tb-btn" data-rel="SPOUSE">＋配偶</button>' +
                        '<button class="fm-tb-btn" data-rel="CHILD">＋子女</button>' +
                        '<button class="fm-tb-btn" data-rel="PARENT">＋父母</button>' +
                        '<button class="fm-tb-btn" data-rel="EX_SPOUSE">标记离异</button>' +
                        '<button class="fm-tb-btn" data-rel="remove">解除关系</button>' +
                        '</div>' : '') +
                '</div>' +
            '</div>';

        document.body.appendChild(wrap);
        wrap.querySelector('[data-act="close"]').onclick = function () { wrap.remove(); state.detail = null; };

        // 保存基础信息
        var saveBtn = document.getElementById('fm-d-save');
        if (saveBtn) saveBtn.onclick = function () {
            var birth = val('fm-d-birth');
            var death = val('fm-d-death');
            if (birth && death && death < birth) { notify('逝世日期不能早于出生日期'); return; }
            saveBtn.disabled = true;
            API.memberSave({
                id: d.id,
                name: val('fm-d-name'),
                gender: Number(val('fm-d-gender') || 0),
                birthDate: birth,
                deathDate: death,
                occupation: val('fm-d-occ'),
                hometown: val('fm-d-home'),
                phone: val('fm-d-phone'),
                address: val('fm-d-addr'),
                bio: val('fm-d-bio'),
                avatarUrl: d.avatarUrl,
                version: d.version
            }).then(function () {
                notify('已保存');
                saveBtn.disabled = false;
                openMemberDrawer(d.id);
                loadTree();
            }).catch(function (e) {
                notify(e.message || '保存失败');
                saveBtn.disabled = false;
            });
        };

        renderPhotos(d);
        renderRelations(d);
    }

    /** 相册渲染 + 拖拽上传 */
    function renderPhotos(d) {
        var grid = document.getElementById('fm-photo-grid');
        if (!grid) return;
        var photos = d.photos || [];
        state.photos = photos;
        grid.innerHTML = photos.map(function (p, i) {
            return '<div class="fm-photo" data-idx="' + i + '">' +
                '<img src="' + esc(p.url) + '" alt="">' +
                (d.writable ? '<div class="fm-photo-del" data-del="' + p.id + '">×</div>' : '') +
            '</div>';
        }).join('') +
            (d.writable ? '<div class="fm-photo-add" id="fm-photo-add"><div class="fm-photo-add big">＋</div><div>上传照片</div></div>' : '');

        grid.querySelectorAll('.fm-photo').forEach(function (el) {
            el.onclick = function (e) {
                if (e.target && e.target.getAttribute && e.target.getAttribute('data-del')) return;
                openLightbox(Number(el.getAttribute('data-idx')));
            };
        });
        grid.querySelectorAll('[data-del]').forEach(function (el) {
            el.onclick = function (e) {
                e.stopPropagation();
                var pid = Number(el.getAttribute('data-del'));
                API.photoDelete(pid).then(function () {
                    notify('已删除照片');
                    openMemberDrawer(d.id);
                }).catch(function (err) { notify(err.message || '删除失败'); });
            };
        });

        var add = document.getElementById('fm-photo-add');
        var input = document.getElementById('fm-photo-input');
        if (add && input) {
            add.onclick = function () { input.click(); };
            input.onchange = function () { uploadPhotos(d.id, input.files); };
            // 拖拽上传
            add.ondragover = function (e) { e.preventDefault(); add.classList.add('fm-drop'); };
            add.ondragleave = function () { add.classList.remove('fm-drop'); };
            add.ondrop = function (e) {
                e.preventDefault();
                add.classList.remove('fm-drop');
                uploadPhotos(d.id, e.dataTransfer.files);
            };
        }
    }

    function uploadPhotos(memberId, files) {
        if (!files || !files.length) return;
        var fd = new FormData();
        Array.prototype.forEach.call(files, function (f) { fd.append('files', f); });
        notify('上传中…');
        upload('/api/family/member/' + memberId + '/photos', fd).then(function (d) {
            if (d && d.code === 0) {
                notify('已上传 ' + (d.data && d.data.count || files.length) + ' 张照片');
                openMemberDrawer(memberId);
            } else notify((d && d.message) || '上传失败');
        }).catch(function () { notify('上传失败'); });
    }

    /** 大图预览（左右切换） */
    function openLightbox(idx) {
        state.lbIndex = idx;
        var photos = state.photos || [];
        if (!photos.length) return;
        var box = document.createElement('div');
        box.className = 'fm-lightbox';
        box.id = 'fm-lightbox';
        var paint = function () {
            var p = photos[state.lbIndex];
            box.innerHTML =
                '<img src="' + esc(p.url) + '" alt="">' +
                '<button class="fm-lb-btn prev">‹</button>' +
                '<button class="fm-lb-btn next">›</button>' +
                '<button class="fm-lb-close">×</button>' +
                '<div class="fm-lb-cap">' + esc(p.caption || (state.lbIndex + 1) + ' / ' + photos.length) + '</div>';
            box.querySelector('.prev').onclick = function () {
                state.lbIndex = (state.lbIndex - 1 + photos.length) % photos.length; paint();
            };
            box.querySelector('.next').onclick = function () {
                state.lbIndex = (state.lbIndex + 1) % photos.length; paint();
            };
            box.querySelector('.fm-lb-close').onclick = function () { box.remove(); };
        };
        paint();
        box.addEventListener('click', function (e) { if (e.target === box) box.remove(); });
        document.addEventListener('keydown', function onKey(e) {
            if (!document.getElementById('fm-lightbox')) {
                document.removeEventListener('keydown', onKey);
                return;
            }
            if (e.key === 'ArrowLeft') { state.lbIndex = (state.lbIndex - 1 + photos.length) % photos.length; paint(); }
            if (e.key === 'ArrowRight') { state.lbIndex = (state.lbIndex + 1) % photos.length; paint(); }
            if (e.key === 'Escape') box.remove();
        });
        document.body.appendChild(box);
    }

    /** 关联亲属列表 */
    function renderRelations(d) {
        var box = document.getElementById('fm-rel-list');
        if (!box) return;
        var rels = d.relations || [];
        if (!rels.length) {
            box.innerHTML = '<div style="font-size:13px;color:#7A7268">还没有登记亲属关系</div>';
        } else {
            box.innerHTML = rels.map(function (r) {
                return '<div class="fm-rel-item" data-id="' + r.memberId + '">' +
                    (r.avatarUrl ? '<img class="fm-rel-av" src="' + esc(r.avatarUrl) + '" alt="">'
                        : '<div class="fm-rel-av">' + esc((r.name || '?').charAt(0)) + '</div>') +
                    '<span>' + esc(r.name) + '</span>' +
                    '<span class="fm-rel-title">' + esc(r.title) + '</span>' +
                '</div>';
            }).join('');
            box.querySelectorAll('.fm-rel-item').forEach(function (el) {
                el.onclick = function () {
                    var id = Number(el.getAttribute('data-id'));
                    var mask = document.getElementById('fm-drawer-mask');
                    if (mask) mask.remove();
                    openMemberDrawer(id);
                    focusMember(id);
                };
            });
        }
        if (d.writable) {
            box.parentNode.querySelectorAll('[data-rel]').forEach(function (b) {
                b.onclick = function () {
                    var act = b.getAttribute('data-rel');
                    if (act === 'remove') { openRemoveRelation(d); return; }
                    if (act === 'EX_SPOUSE') { openDivorceForm(d); return; }
                    openRelationForm(d.id, act, {
                        SPOUSE: '添加配偶', CHILD: '添加子女', PARENT: '添加父母',
                        ADOPTED_CHILD: '添加养子女', STEP_PARENT: '添加继父母'
                    }[act] || '添加亲属');
                };
            });
        }
    }

    /** 添加亲属：可选择已有成员，或现场新建 */
    function openRelationForm(memberId, type, title) {
        var familyId = state.family && state.family.id;
        API.tree(familyId, state.direction, 0, [], state.shareToken).then(function (vo) {
            var options = (vo.nodes || []).map(function (n) {
                return '<option value="' + n.memberId + '">' + esc(n.name) + '</option>';
            }).join('');
            var html =
                '<div class="fm-modal-title">' + esc(title) + '</div>' +
                '<div class="fm-field" style="margin-bottom:10px">' +
                    '<label>选择已有成员</label>' +
                    '<select id="fm-r-exist" class="fm-select"><option value="">— 新建一位成员 —</option>' + options + '</select>' +
                '</div>' +
                '<div class="fm-form-row">' +
                    '<div class="fm-field"><label>新成员姓名</label><input id="fm-r-name" class="fm-input" placeholder="留空则表示选择已有成员"></div>' +
                    '<div class="fm-field"><label>性别</label><select id="fm-r-gender" class="fm-select">' +
                        '<option value="0">未知</option><option value="1">男</option><option value="2">女</option></select></div>' +
                '</div>' +
                '<div class="fm-field" style="margin-bottom:10px">' +
                    '<label>关系描述（可选）</label>' +
                    '<input id="fm-r-desc" class="fm-input" maxlength="255" placeholder="如：1988 年结婚 / 自幼过继">' +
                '</div>' +
                '<div class="fm-hint">提示：添加子女时，若本人已有配偶，配偶会自动登记为另一位家长（父 + 母两条关系）。</div>' +
                '<div class="fm-modal-foot">' +
                    '<button class="fm-btn ghost" data-act="cancel">取消</button>' +
                    '<button class="fm-btn" data-act="ok">确定</button>' +
                '</div>';
            var mask = modal(html, function (m) {
                m.querySelector('[data-act="cancel"]').onclick = function () { m.remove(); };
                m.querySelector('[data-act="ok"]').onclick = function () {
                    var exist = val('fm-r-exist');
                    var body = {
                        memberId: Number(memberId),
                        relationType: type,
                        relativeId: exist ? Number(exist) : null,
                        relationDesc: val('fm-r-desc')
                    };
                    if (!exist) {
                        var nm = val('fm-r-name');
                        if (!nm) { notify('请填写姓名，或选择已有成员'); return; }
                        body.newRelative = { name: nm, gender: Number(val('fm-r-gender') || 0) };
                    }
                    var btn = this;
                    btn.disabled = true;
                    API.relationAdd(body).then(function (res) {
                        notify('关系已建立');
                        m.remove();
                        // 新成员若被折叠，自动展开其所在分支
                        if (res && res.memberId) state.expanded[res.memberId] = true;
                        loadTree();
                        if (state.detail) openMemberDrawer(state.detail.id);
                    }).catch(function (e) {
                        notify(e.message || '操作失败');
                        btn.disabled = false;
                    });
                };
            });
            return mask;
        }).catch(function (e) { notify(e.message || '加载成员列表失败'); });
    }

    /** 标记离异：从配偶列表里选一个 */
    function openDivorceForm(d) {
        var spouses = (d.relations || []).filter(function (r) { return r.relationType === 'SPOUSE'; });
        if (!spouses.length) { notify('该成员当前没有配偶'); return; }
        var html =
            '<div class="fm-modal-title">标记离异</div>' +
            '<div class="fm-field"><label>选择配偶</label><select id="fm-dv-sel" class="fm-select">' +
            spouses.map(function (s) {
                return '<option value="' + s.memberId + '">' + esc(s.name) + '</option>';
            }).join('') + '</select></div>' +
            '<div class="fm-hint">标记后两人之间的连线将变为虚线，并显示为「前配偶」。</div>' +
            '<div class="fm-modal-foot">' +
                '<button class="fm-btn ghost" data-act="cancel">取消</button>' +
                '<button class="fm-btn" data-act="ok">确定</button>' +
            '</div>';
        var mask = modal(html, function (m) {
            m.querySelector('[data-act="cancel"]').onclick = function () { m.remove(); };
            m.querySelector('[data-act="ok"]').onclick = function () {
                API.relationAdd({
                    memberId: d.id,
                    relativeId: Number(val('fm-dv-sel')),
                    relationType: 'EX_SPOUSE'
                }).then(function () {
                    notify('已标记为离异');
                    m.remove();
                    loadTree();
                    openMemberDrawer(d.id);
                }).catch(function (e) { notify(e.message || '操作失败'); });
            };
        });
        return mask;
    }

    /** 解除关系 */
    function openRemoveRelation(d) {
        var rels = d.relations || [];
        if (!rels.length) { notify('暂无可解除的关系'); return; }
        var html =
            '<div class="fm-modal-title">解除关系</div>' +
            '<div class="fm-field"><label>选择要解除的亲属</label><select id="fm-rm-sel" class="fm-select">' +
            rels.map(function (r) {
                return '<option value="' + r.memberId + '">' + esc(r.name) + '（' + esc(r.title) + '）</option>';
            }).join('') + '</select></div>' +
            '<div class="fm-warn">解除后两人的连线将消失（夫妻双向、父子反向关系会一并清除），成员本身不会被删除。</div>' +
            '<div class="fm-modal-foot">' +
                '<button class="fm-btn ghost" data-act="cancel">取消</button>' +
                '<button class="fm-btn danger" data-act="ok">解除</button>' +
            '</div>';
        var mask = modal(html, function (m) {
            m.querySelector('[data-act="cancel"]').onclick = function () { m.remove(); };
            m.querySelector('[data-act="ok"]').onclick = function () {
                API.relationRemove(d.id, Number(val('fm-rm-sel'))).then(function () {
                    notify('已解除关系');
                    m.remove();
                    loadTree();
                    openMemberDrawer(d.id);
                }).catch(function (e) { notify(e.message || '操作失败'); });
            };
        });
        return mask;
    }

    // ======================================================================
    // 页面 4：家族大事记
    // ======================================================================

    function openTimeline() {
        state.view = 'timeline';
        var fid = state.family && state.family.id;
        var r = root();
        if (!r) return;
        r.innerHTML =
            '<div class="fm-wrap">' +
                '<div class="fm-head">' +
                    '<div>' +
                        '<div class="fm-title">' + esc((state.family && state.family.name) || '家族') + ' · 大事记</div>' +
                        '<div class="fm-sub">按时间倒序记录家族的重要时刻</div>' +
                    '</div>' +
                    '<div style="display:flex;gap:8px">' +
                        '<button class="fm-btn ghost" id="fm-tl-back">‹ 返回族谱</button>' +
                        (state.readonly ? '' : '<button class="fm-btn" id="fm-tl-add">＋ 新增事件</button>') +
                    '</div>' +
                '</div>' +
                '<div id="fm-timeline" class="fm-timeline"><div class="fm-empty">加载中…</div></div>' +
            '</div>';
        var back = document.getElementById('fm-tl-back');
        if (back) back.onclick = function () { openTree(fid); };
        var add = document.getElementById('fm-tl-add');
        if (add) add.onclick = function () { openEventModal(null); };
        loadEvents();
    }

    function loadEvents() {
        API.events(state.family.id).then(function (list) {
            state.events = list || [];
            var box = document.getElementById('fm-timeline');
            if (!box) return;
            if (!state.events.length) {
                box.innerHTML = '<div class="fm-empty">还没有大事记，记录第一个家族时刻吧 📜</div>';
                return;
            }
            box.innerHTML = state.events.map(function (e) {
                var members = (e.memberNames || []).map(function (n) {
                    return '<span class="fm-tl-member">' + esc(n) + '</span>';
                }).join('');
                return '<div class="fm-tl-item" data-id="' + e.id + '">' +
                    '<div class="fm-tl-date">' + esc(e.eventDate) + '</div>' +
                    '<div class="fm-tl-title">' + esc(e.title) + '</div>' +
                    '<div class="fm-tl-content">' + esc(e.content) + '</div>' +
                    (members ? '<div class="fm-tl-members">' + members + '</div>' : '') +
                    (state.readonly ? '' : '<div style="margin-top:10px;text-align:right">' +
                        '<button class="fm-tb-btn" data-edit="' + e.id + '">编辑</button> ' +
                        '<button class="fm-tb-btn" data-del="' + e.id + '">删除</button></div>') +
                '</div>';
            }).join('');
            if (!state.readonly) {
                box.querySelectorAll('[data-edit]').forEach(function (b) {
                    b.onclick = function () {
                        var id = Number(b.getAttribute('data-edit'));
                        var ev = state.events.find(function (x) { return x.id === id; });
                        openEventModal(ev);
                    };
                });
                box.querySelectorAll('[data-del]').forEach(function (b) {
                    b.onclick = function () {
                        var id = Number(b.getAttribute('data-del'));
                        confirmBox('删除事件', '<div class="fm-warn">确定删除这条大事记？此操作不可撤销。</div>',
                            '删除', function () {
                                return API.eventDelete(id).then(function () {
                                    notify('已删除');
                                    loadEvents();
                                });
                            });
                    };
                });
            }
        }).catch(function (e) {
            var box = document.getElementById('fm-timeline');
            if (box) box.innerHTML = '<div class="fm-empty">加载失败：' + esc(e.message) + '</div>';
        });
    }

    /** 新增 / 编辑大事记（关联成员多选） */
    function openEventModal(ev) {
        var isEdit = !!ev;
        API.tree(state.family.id, state.direction, 0, [], state.shareToken).then(function (vo) {
            var opts = (vo.nodes || []).map(function (n) {
                var sel = isEdit && (ev.memberIds || []).indexOf(n.memberId) >= 0 ? ' selected' : '';
                return '<option value="' + n.memberId + '"' + sel + '>' + esc(n.name) + '</option>';
            }).join('');
            var html =
                '<div class="fm-modal-title">' + (isEdit ? '编辑大事记' : '新增大事记') + '</div>' +
                '<div class="fm-field" style="margin-bottom:10px">' +
                    '<label>事件标题 *</label>' +
                    '<input id="fm-e-title" class="fm-input" maxlength="128" value="' + esc(isEdit ? ev.title : '') + '">' +
                '</div>' +
                '<div class="fm-field" style="margin-bottom:10px">' +
                    '<label>事件日期 *</label>' +
                    '<input id="fm-e-date" type="date" class="fm-input" value="' + esc(isEdit ? ev.eventDate : '') + '">' +
                '</div>' +
                '<div class="fm-field" style="margin-bottom:10px">' +
                    '<label>事件内容</label>' +
                    '<textarea id="fm-e-content" class="fm-textarea" maxlength="4000">' + esc(isEdit ? ev.content : '') + '</textarea>' +
                '</div>' +
                '<div class="fm-field" style="margin-bottom:6px">' +
                    '<label>关联成员（按住 Ctrl / Cmd 可多选）</label>' +
                    '<select id="fm-e-members" class="fm-select" multiple size="6">' + opts + '</select>' +
                '</div>' +
                '<div class="fm-modal-foot">' +
                    '<button class="fm-btn ghost" data-act="cancel">取消</button>' +
                    '<button class="fm-btn" data-act="ok">保存</button>' +
                '</div>';
            var mask = modal(html, function (m) {
                m.querySelector('[data-act="cancel"]').onclick = function () { m.remove(); };
                m.querySelector('[data-act="ok"]').onclick = function () {
                    var title = val('fm-e-title');
                    var date = val('fm-e-date');
                    if (!title) { notify('请填写事件标题'); return; }
                    if (!date) { notify('请选择事件日期'); return; }
                    var sel = document.getElementById('fm-e-members');
                    var ids = [];
                    if (sel) {
                        Array.prototype.forEach.call(sel.options, function (o) {
                            if (o.selected) ids.push(Number(o.value));
                        });
                    }
                    var body = {
                        id: isEdit ? ev.id : null,
                        familyId: isEdit ? undefined : state.family.id,
                        title: title,
                        eventDate: date,
                        content: val('fm-e-content'),
                        memberIds: ids
                    };
                    var btn = this;
                    btn.disabled = true;
                    API.eventSave(body).then(function () {
                        notify(isEdit ? '已更新' : '已新增');
                        m.remove();
                        loadEvents();
                    }).catch(function (e) {
                        notify(e.message || '保存失败');
                        btn.disabled = false;
                    });
                };
            });
            return mask;
        }).catch(function (e) { notify(e.message || '加载成员失败'); });
    }

    // ======================================================================
    // 页面 5：家族设置
    // ======================================================================

    function openSettings() {
        var f = state.family || {};
        var html =
            '<div class="fm-modal-title">家族设置</div>' +
            '<div class="fm-field" style="margin-bottom:10px">' +
                '<label>家族名称 *</label><input id="fm-s-name" class="fm-input" maxlength="64" value="' + esc(f.name) + '">' +
            '</div>' +
            '<div class="fm-field" style="margin-bottom:10px">' +
                '<label>家族简介</label><textarea id="fm-s-intro" class="fm-textarea" maxlength="500">' + esc(f.intro || '') + '</textarea>' +
            '</div>' +
            '<div class="fm-field" style="margin-bottom:10px">' +
                '<label>可见性</label><select id="fm-s-vis" class="fm-select">' +
                    '<option value="0"' + (f.visibility === 0 ? ' selected' : '') + '>私有（仅自己与家族成员可见）</option>' +
                    '<option value="1"' + (f.visibility === 1 ? ' selected' : '') + '>链接只读（凭分享链接查看）</option>' +
                    '<option value="2"' + (f.visibility === 2 ? ' selected' : '') + '>公开（所有登录用户可见）</option>' +
                '</select>' +
            '</div>' +
            '<div class="fm-field" style="margin-bottom:6px">' +
                '<label>封面图</label>' +
                '<div style="display:flex;gap:8px;align-items:center">' +
                    '<input type="file" id="fm-s-file" accept="image/*" style="flex:1">' +
                    '<button class="fm-tb-btn" id="fm-s-up">上传</button>' +
                '</div>' +
                '<input type="hidden" id="fm-s-cover" value="' + esc(f.coverUrl || '') + '">' +
                '<input type="hidden" id="fm-s-version" value="' + (f.version || 0) + '">' +
            '</div>' +
            '<div class="fm-hint">成员数：' + (f.memberCount || 0) + ' 位</div>' +
            '<div class="fm-modal-foot">' +
                '<button class="fm-btn danger" data-act="del">删除家族</button>' +
                '<button class="fm-btn ghost" data-act="cancel">取消</button>' +
                '<button class="fm-btn" data-act="ok">保存</button>' +
            '</div>';
        var mask = modal(html, function (m) {
            m.querySelector('[data-act="cancel"]').onclick = function () { m.remove(); };
            m.querySelector('[data-act="ok"]').onclick = function () {
                if (!val('fm-s-name')) { notify('请填写家族名称'); return; }
                var btn = this;
                btn.disabled = true;
                API.update({
                    id: f.id,
                    name: val('fm-s-name'),
                    intro: val('fm-s-intro'),
                    coverUrl: val('fm-s-cover'),
                    visibility: Number(val('fm-s-vis') || 0),
                    version: Number(val('fm-s-version') || 0)
                }).then(function () {
                    notify('已保存');
                    m.remove();
                    openTree(f.id);
                }).catch(function (e) {
                    notify(e.message || '保存失败');
                    btn.disabled = false;
                });
            };
            m.querySelector('[data-act="del"]').onclick = function () {
                confirmBox('删除家族',
                    '<div class="fm-warn">删除后家族内的成员、关系、相册与大事记都会被清理，且不可恢复。</div>',
                    '确认删除', function () {
                        return API.remove(f.id).then(function () {
                            notify('家族已删除');
                            m.remove();
                            open();
                        });
                    });
            };
            var up = m.querySelector('#fm-s-up');
            if (up) up.onclick = function () {
                var file = document.getElementById('fm-s-file');
                if (!file || !file.files || !file.files[0]) { notify('请先选择图片'); return; }
                var fd = new FormData();
                fd.append('file', file.files[0]);
                fd.append('prefix', 'family/cover');
                upload('/api/file/upload', fd).then(function (d) {
                    if (d && d.code === 0 && d.data) {
                        document.getElementById('fm-s-cover').value = d.data.url;
                        notify('封面已上传，请点击保存');
                    } else notify('上传失败');
                }).catch(function () { notify('上传失败'); });
            };
        });
        return mask;
    }

    /** 分享链接生成与复制 */
    function openShareDialog() {
        var fid = state.family && state.family.id;
        var html =
            '<div class="fm-modal-title">分享家族</div>' +
            '<div class="fm-field" style="margin-bottom:10px">' +
                '<label>只读分享链接（任何人打开都可查看，不能编辑）</label>' +
                '<input id="fm-sh-url" class="fm-input" readonly value="生成中…">' +
            '</div>' +
            '<div class="fm-hint">提示：需先把家族可见性设为「链接只读」或「公开」，否则分享链接会失效。</div>' +
            '<div class="fm-modal-foot">' +
                '<button class="fm-btn ghost" data-act="revoke">撤销链接</button>' +
                '<button class="fm-btn ghost" data-act="cancel">关闭</button>' +
                '<button class="fm-btn" data-act="copy">复制链接</button>' +
            '</div>';
        var mask = modal(html, function (m) {
            var input = m.querySelector('#fm-sh-url');
            API.genShare(fid).then(function (d) {
                var token = d && d.token;
                input.value = location.origin + '/index.html#/family/share/' + token;
            }).catch(function (e) {
                input.value = '生成失败：' + (e.message || '');
            });
            m.querySelector('[data-act="cancel"]').onclick = function () { m.remove(); };
            m.querySelector('[data-act="copy"]').onclick = function () {
                input.select();
                try {
                    document.execCommand('copy');
                    notify('链接已复制');
                } catch (e) {
                    if (navigator.clipboard) {
                        navigator.clipboard.writeText(input.value).then(function () { notify('链接已复制'); });
                    } else notify('请手动复制');
                }
            };
            m.querySelector('[data-act="revoke"]').onclick = function () {
                API.revokeShare(fid).then(function () {
                    notify('分享链接已撤销');
                    m.remove();
                }).catch(function (e) { notify(e.message || '撤销失败'); });
            };
        });
        return mask;
    }

    // ======================================================================
    // 分享链接入口：#/family/share/<token>
    // ======================================================================

    function checkShareHash() {
        var m = /#\/family\/share\/([A-Za-z0-9]+)/.exec(location.hash || '');
        if (!m) return false;
        if (!App.token) return false;
        openShare(m[1]);
        return true;
    }

    window.addEventListener('hashchange', checkShareHash);
    // 登录后可能才拿到 token，做几次延迟尝试
    (function tryShare() {
        var times = 0;
        var timer = setInterval(function () {
            times++;
            if (checkShareHash() || times > 20) clearInterval(timer);
        }, 500);
    })();

    // ======================================================================
    // 对外
    // ======================================================================

    return {
        open: open,
        openTree: openTree,
        openShare: openShare,
        openTimeline: openTimeline,
        openSettings: openSettings,
        renderList: renderList,
        state: state
    };
})();
