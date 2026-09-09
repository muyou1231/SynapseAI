/* 会话列表 / 聊天窗口 / 消息收发 */
window.Chat = (function () {

    function el(id) { return document.getElementById(id); }

    /** 分页加载状态：每个会话的当前页码 + 是否还有更多 */
    var pageInfo = {};  // key "USER:123" -> { page: 0, hasMore: true, loading: false }

    /** ⑨ 消息炸弹默认倒计时秒数（发送方设定，接收方须在此内回复以拆弹） */
    var DEFAULT_BOMB_SECONDS = 30;

    /** 类型安全地判断某发送方是否已被屏蔽加急弹窗（peerId 可能为 number / string） */
    function isPeerMuted(peerId) {
        const p = Number(peerId);
        return App.mutedUrgentPeers.some(function (x) { return Number(x) === p; });
    }

    /** 从服务端重新拉取「已屏蔽加急」列表，重建前端权威状态（避免本地状态与数据库不同步/重复项） */
    function reloadMutedPeers(cb) {
        Api.urgentMuteList().then(function (d) {
            if (d && d.code === 0 && Array.isArray(d.data)) {
                App.mutedUrgentPeers = d.data.map(function (x) { return Number(x.peerId); });
            }
            if (cb) cb();
        });
    }

    /** 新消息到达时的丝滑滚动：只有在已接近底部时才用平滑滚动，避免深翻历史时被强行拽回底部时出现长时间滚动动画 */
    function scrollToBottom(box, smooth) {
        if (!box) return;
        const distance = box.scrollHeight - box.clientHeight - box.scrollTop;
        if (smooth && distance < 600 && typeof box.scrollTo === 'function') {
            box.scrollTo({ top: box.scrollHeight, behavior: 'smooth' });
        } else {
            box.scrollTop = box.scrollHeight;
        }
    }

    function previewOf(wm) {
        if (!wm) return '';
        if (wm.recalled) return '[撤回]';
        const prefix = wm.urgent ? '⚡ ' : '';
        let body;
        if (wm.type === 'IMAGE') body = '[图片]';
        else if (wm.type === 'VOICE') body = '[语音]';
        else if (wm.type === 'CALL') body = '[通话]';
        else body = (wm.content || '');
        return prefix + body;
    }

    /** 语音通话记录气泡文案：根据查看者视角（发起方/接听方）与通话结果展示不同文案 */
    function callRecordInner(wm) {
        let r = 'cancel', d = 0;
        try { const o = JSON.parse(wm.content || '{}'); if (o && o.r) r = o.r; if (o && o.d) d = o.d || 0; } catch (e) {}
        const isCaller = wm.senderId === App.user.id;
        let text;
        if (r === 'connected') {
            const s = Math.max(0, d | 0);
            const m = Math.floor(s / 60), ss = s % 60;
            text = '通话时长 ' + (m < 10 ? '0' : '') + m + ':' + (ss < 10 ? '0' : '') + ss;
        } else if (r === 'reject') {
            text = isCaller ? '对方拒绝了通话' : '已拒绝';
        } else if (r === 'busy') {
            text = isCaller ? '对方忙线未接听' : '对方忙线';
        } else if (r === 'noanswer') {
            text = isCaller ? '对方无应答' : '未接听';
        } else if (r === 'failed') {
            text = '通话未建立';
        } else { // cancel
            text = isCaller ? '已取消' : '对方已取消';
        }
        return '<div class="call-record-pill">📞 ' + App.escapeHtml(text) + '</div>';
    }

    /** 是否仍在撤回时间窗内（发送后 2 分钟） */
    function withinRecallWindow(wm) {
        try {
            const t = new Date((wm.createTime || '').replace(/-/g, '/'));
            return (Date.now() - t.getTime()) < 2 * 60 * 1000;
        } catch (e) { return false; }
    }

    /** 把一条消息节点替换为「XX 撤回了一条消息」系统提示 */
    function replaceWithRecalled(wm) {
        const box = el('chat-messages');
        const node = box.querySelector('[data-mid="' + wm.id + '"]');
        if (node) {
            const tip = document.createElement('div');
            tip.className = 'sys-tip';
            tip.setAttribute('data-mid', wm.id);
            const who = wm.senderId === App.user.id ? '你' : (App.current ? App.current.name : '对方');
            tip.textContent = who + ' 撤回了一条消息';
            node.parentNode.replaceChild(tip, node);
        }
        // 同时更新会话列表最后一条预览
        const key = (wm.targetType === 'USER')
            ? 'USER:' + (wm.senderId === App.user.id ? wm.targetId : wm.senderId)
            : 'GROUP:' + wm.targetId;
        const conv = App.conversations.find(function (c) { return c.type + ':' + c.id === key; });
        if (conv && conv.lastMessage && conv.lastMessage.id === wm.id) {
            conv.lastMessage.recalled = true;
            if (App.activeTab === 'chat') renderConversations();
        }
    }

    /** 发起撤回 */
    function recallMessage(id) {
        Api.recall(id).then(function (d) {
            if (d && d.code === 0) {
                replaceWithRecalled({ id: id, senderId: App.user.id, targetType: App.current ? App.current.type : 'USER', targetId: App.current ? App.current.id : null });
            } else {
                App.notify((d && d.message) || '撤回失败');
            }
        });
    }

    /** 会话列表排序：置顶的永远在最上面，其余按最后一条消息时间倒序（最新消息位于置顶块下方最前） */
    function sortConversations() {
        App.conversations.sort(function (a, b) {
            if (a.pinned !== b.pinned) return a.pinned ? -1 : 1;
            const ta = a.lastMessage ? new Date((a.lastMessage.createTime || '').replace(/-/g, '/')).getTime() : 0;
            const tb = b.lastMessage ? new Date((b.lastMessage.createTime || '').replace(/-/g, '/')).getTime() : 0;
            return tb - ta;
        });
    }

    function renderConversations() {
        sortConversations();
        const list = el('side-list');
        list.innerHTML = '';
        if (!App.conversations.length) {
            list.innerHTML += '<div class="empty-tip">暂无会话，去通讯录加好友或建群吧</div>';
            updateTabBadge();
            return;
        }
        App.conversations.forEach(function (c) {
            const item = document.createElement('div');
            item.className = 'item' + (App.current && App.current.type === c.type && App.current.id === c.id ? ' active' : '');
            // 单聊会话名优先显示好友备注
            let convName = (c.type === 'USER') ? App.friendName(c.id, c.name || '') : (c.name || '');
            // 对方正在输入：列表副标题直接显示「正在输入中…」
            let subHtml;
            if (c.type === 'USER' && App.typing[c.id]) {
                subHtml = '<span class="typing-sub">正在输入中…</span>';
            } else {
                const sub = previewOf(c.lastMessage);
                const time = c.lastMessage ? App.shortTime(c.lastMessage.createTime) : '';
                const lm = c.lastMessage;
                const readMark = (lm && lm.senderId === App.user.id && lm.read)
                    ? '<span class="read-mark">已读</span> ' : '';
                subHtml = readMark + App.escapeHtml(time + (time && sub ? '  ' : '') + sub);
            }
            let tag = '';
            if (c.type === 'GROUP' && c.deleted) tag = '<span class="dissolved-tag">已解散</span>';
            item.innerHTML = (c.pinned ? '<span class="pin-flag"></span>' : '') +
                App.entityClickable(c, c.type, c.id) +
                '<div class="meta">' +
                '<div class="name">' + App.escapeHtml(convName) + tag +
                (c.unread ? '<span class="badge">' + c.unread + '</span>' : '') + '</div>' +
                '<div class="sub">' + subHtml + '</div>' +
                '</div>';
            const pinBtn = document.createElement('button');
            pinBtn.className = 'op pin';
            pinBtn.textContent = c.pinned ? '取消置顶' : '置顶';
            pinBtn.onclick = function (e) {
                e.stopPropagation();
                Api.pin(c.type, c.id, !c.pinned).then(function (d) {
                    if (d && d.code === 0) {
                        c.pinned = !c.pinned;
                        renderConversations(); // 内部已按「置顶优先 + 时间倒序」排序
                    }
                });
            };
            item.appendChild(pinBtn);
            // 删除会话（仅从列表移除，不清除聊天记录）
            const delBtn = document.createElement('button');
            delBtn.className = 'op del';
            delBtn.textContent = '删除';
            delBtn.title = '删除此会话（聊天记录保留）';
            delBtn.onclick = async function (e) {
                e.stopPropagation();
                const tip = '确定删除此会话？\n（聊天记录不会被清除，从通讯录/群列表重新打开即可恢复）';
                if (!await App.confirm(tip, { danger: true })) return;
                Api.deleteConversation(c.type, c.id).then(function (d) {
                    if (d && d.code === 0) {
                        // 从列表中移除
                        App.conversations = App.conversations.filter(function (x) {
                            return !(x.type === c.type && x.id === c.id);
                        });
                        // 如果删除的是当前会话，关闭聊天窗口
                        if (App.current && App.current.type === c.type && App.current.id === c.id) {
                            closeConversation();
                        }
                        renderConversations();
                        App.notify('已删除会话');
                    }
                });
            };
            item.appendChild(delBtn);
            item.onclick = function () { openConversation(c.type, c.id, c.name); };
            list.appendChild(item);
        });
        updateTabBadge();
    }

    /** 会话管理：弹出「新建会话」对话框，填写标题后创建空白私人会话并打开 */

    /** 会话管理：弹出「新建会话」对话框，填写标题后创建空白私人会话并打开 */

    function refreshConversations() {
        Api.conversations().then(function (data) {
            if (!data || data.code !== 0) return;
            const prev = {};
            App.conversations.forEach(function (c) { prev[c.type + ':' + c.id] = c.unread || 0; });
            App.conversations = (data.data || []).map(function (c) {
                c.unread = prev[c.type + ':' + c.id] || 0;
                return c;
            });
            sortConversations(); // 保证内存中的会话列表始终「置顶优先 + 时间倒序」
            // 确保所有群会话都已订阅 WS 主题，保证群消息实时推送
            // （subscribeMyGroups 仅在连接时订阅已存在的群；新建/新加入的群靠这里兜底）
            if (window.Group) {
                App.conversations.forEach(function (c) {
                    if (c.type === 'GROUP') Group.ensureSubscribe(c.id);
                });
            }
            if (App.activeTab === 'chat') renderConversations();
        });
    }

    /** 断线重连后补拉「当前会话缺失的消息」：取已渲染消息的最大 id 作为 afterId 增量拉取并追加。
     *  appendBubble 自带 data-mid 去重，不会重复渲染；仅当用户本就停在底部时才自动滚动到底。 */
    function syncOpenConversation() {
        const cur = App.current;
        if (!cur) return;
        if (!window.Api || !Api.sync) return;
        const box = el('chat-messages');
        if (!box) return;
        let maxId = 0;
        box.querySelectorAll('[data-mid]').forEach(function (n) {
            const m = n.getAttribute('data-mid');
            if (m && m.indexOf('tmp-') !== 0) {
                const id = Number(m);
                if (!isNaN(id) && id > maxId) maxId = id;
            }
        });
        Api.sync(cur.type, cur.id, maxId).then(function (d) {
            if (!d || d.code !== 0 || !Array.isArray(d.data) || !d.data.length) return;
            const atBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 80;
            d.data.forEach(function (wm) { appendBubble(wm, false); });
            if (atBottom) box.scrollTop = box.scrollHeight;
            // 新到达的是对方消息时，标记已读并通知对方（与 onIncoming 行为一致）
            const hasIncoming = d.data.some(function (wm) { return wm.senderId !== App.user.id; });
            if (hasIncoming) markCurrentRead();
        }).catch(function () {});
    }

    function openConversation(type, id, name) {
        App.current = { type: type, id: id, name: (type === 'USER') ? App.friendName(id, name || '') : (name || '') };
        // 恢复会话：若该会话曾被删除，重新打开即标记 deleted=0（下次会话列表重新出现）。
        // 已解散的群不会出现在群列表，无法触发恢复，符合预期。
        Api.restoreConversation(type, id).then(function () {
            // 仅当该会话之前不在列表（即被删过）才刷新，避免正常打开会话时列表跳动
            var exist = App.conversations.find(function (c) { return c.type === type && c.id === id; });
            if (!exist && window.Chat) Chat.refreshConversations();
        }).catch(function () {});
        // 从通讯录/群列表等入口打开会话：同步把侧边/底部菜单切到「消息」并刷新会话列表，
        // 避免菜单仍停在「通讯录」而聊天窗口已打开的不一致（移动端随后再切全屏聊天）。
        if (App.activeTab !== 'chat') {
            App.switchTab('chat');
        }
        renderChatHeader(type, id, name);
        // 手机端：进入会话即切到全屏聊天视图
        if (App.isMobile()) App.setMobileView('chat');
        el('chat-messages').innerHTML = '';
        renderTyping();
        // 清除未读
        const conv = App.conversations.find(function (c) { return c.type === type && c.id === id; });
        if (conv) conv.unread = 0;
        renderConversations();
        updateTabBadge();
        if (type === 'GROUP' && window.Group) Group.ensureSubscribe(id);
        // 群聊已解散：保留历史但禁用输入
        if (type === 'GROUP' && conv && conv.deleted) {
            setInputEnabled(false);
            showGroupBanner('该群聊已被群主解散，仅可查看历史消息');
        } else {
            setInputEnabled(true);
            hideGroupBanner();
        }
        // 重置分页状态
        var key = type + ':' + id;
        pageInfo[key] = { page: 0, hasMore: true, loading: false };
        loadHistory(type, id, 0);
        // 绑定滚动加载更多
        bindScrollMore(type, id);
    }

    /** 群聊被解散时禁用输入框并提示 */
    function onGroupDissolved(groupId) {
        refreshConversations();
        const cur = App.current;
        if (cur && cur.type === 'GROUP' && cur.id === groupId) {
            setInputEnabled(false);
            showGroupBanner('该群聊已被群主解散，仅可查看历史消息');
        }
    }

    function showGroupBanner(text) {
        const b = el('group-banner');
        if (!b) return;
        b.textContent = text;
        b.classList.remove('hidden');
    }

    function hideGroupBanner() {
        const b = el('group-banner');
        if (!b) return;
        b.classList.add('hidden');
        b.textContent = '';
    }

    /** 渲染聊天窗口头部：标题 + 操作按钮（群成员 / 屏蔽加急 / 关闭） */
    function renderChatHeader(type, id, name) {
        const header = el('chat-header');
        // 单聊优先显示好友备注
        const dispName = (type === 'USER') ? App.friendName(id, name || '') : (name || '');
        let html = '<button class="mobile-back" onclick="App.setMobileView(\'list\')" title="返回">‹</button>';
        html += '<span class="chat-title">' + App.escapeHtml(dispName) + '</span>';
        // AI 助手会话：标题旁显示实时状态点（在线绿/离线灰/维修橙/忙碌黄）
        const f = (type === 'USER') ? App.friendsById[id] : null;
        if (f && f.isAssistant) {
            const st = (App.aiStatus[id] || f.aiStatus || 'ONLINE').toUpperCase();
            let cls = 'online-dot';
            if (st === 'OFFLINE') cls += ' offline';
            else if (st === 'MAINTENANCE') cls += ' maintenance';
            else if (st === 'BUSY') cls += ' busy';
            const title = st === 'OFFLINE' ? '离线' : (st === 'MAINTENANCE' ? '维修中' : (st === 'BUSY' ? '忙碌' : '在线'));
            html += '<span class="' + cls + '" title="' + title + '" style="margin-left:2px;vertical-align:middle"></span>';
        }
        html += '<div class="chat-actions">';
        if (type === 'GROUP') {
            html += '<button class="op ok" onclick="Group.openMemberModal(' + id + ')">群成员</button>';
        } else if (type === 'USER') {
            html += '<button class="op" onclick="App.showFriendDetail(' + id + ')" title="查看好友详细信息">资料</button>';
            const muted = isPeerMuted(id);
            html += '<button class="op ' + (muted ? 'del muted' : 'warn') + '" onclick="Chat.toggleUrgentMute()" title="'
                + (muted ? '已屏蔽对方加急弹窗，点击取消屏蔽' : '屏蔽对方的加急弹窗') + '">'
                + (muted ? '🔕 取消屏蔽' : '🔔 屏蔽加急') + '</button>';
        }
        html += '<button class="op call" onclick="Call.startCallFromCurrent()" title="发起语音通话">📞</button>';
        html += '<button class="op close" onclick="Chat.closeConversation()" title="关闭聊天窗口">✕</button>';
        html += '</div>';
        header.innerHTML = html;
    }

    /** 关闭当前聊天窗口（清空会话与消息，禁用输入） */
    function closeConversation() {
        App.current = null;
        el('chat-header').innerHTML = '选择一个会话开始聊天';
        el('chat-messages').innerHTML = '';
        el('chat-messages').onscroll = null;
        hideGroupBanner();
        setInputEnabled(false);
    }

    /** 更新「消息」Tab 的未读角标（合计；超过 99 显示 99+），侧边栏与底部 tabbar 同步 */
    function updateTabBadge() {
        let total = 0;
        App.conversations.forEach(function (c) { total += (c.unread || 0); });
        const badge = el('chat-tab-badge');
        const mbadge = el('mtab-chat-badge');
        const apply = function (b) {
            if (!b) return;
            if (total <= 0) { b.textContent = ''; b.classList.remove('show'); }
            else { b.textContent = total > 99 ? '99+' : String(total); b.classList.add('show'); }
        };
        apply(badge);
        apply(mbadge);
    }

    function loadHistory(type, id, page) {
        var key = type + ':' + id;
        var info = pageInfo[key] || (pageInfo[key] = { page: 0, hasMore: true, loading: false });
        if (info.loading) return;
        info.loading = true;
        Api.history(type, id, page || 0, 30).then(function (data) {
            info.loading = false;
            const box = el('chat-messages');
            if (!data || data.code !== 0 || !data.data) {
                if (page === 0) {
                    box.innerHTML = '';
                }
                markCurrentRead();
                return;
            }
            // 分页结果可能是 {items, total, hasMore} 或直接是数组（兼容旧格式）
            var items = data.data.items || data.data;
            // 双保险：过滤掉任何带 deleted=true 的消息（正常后端已过滤）
            items = items.filter(function (wm) { return !(wm && wm.deleted === true); });
            info.hasMore = data.data.hasMore !== undefined ? data.data.hasMore : false;
            info.page = page || 0;

            if (page === 0) {
                // 首次加载：清空并渲染
                box.innerHTML = '';
                items.forEach(function (wm) { appendBubble(wm, false); });
                box.scrollTop = box.scrollHeight;
            } else {
                // 上滑加载更多：在顶部插入旧消息
                var prevScrollHeight = box.scrollHeight;
                var prevScrollTop = box.scrollTop;
                // 在最前面插入
                var frag = document.createDocumentFragment();
                items.forEach(function (wm) {
                    var time = document.createElement('div');
                    time.className = 'msg-time';
                    time.textContent = App.msgTime(wm.createTime);
                    frag.appendChild(time);
                    frag.appendChild(bubbleNode(wm));
                });
                box.insertBefore(frag, box.firstChild);
                // 保持滚动位置（不跳到顶部）
                box.scrollTop = prevScrollTop + (box.scrollHeight - prevScrollHeight);
            }

            // 如果没有消息且是首次加载，显示空提示
            if (page === 0 && items.length === 0) {
                var empty = document.createElement('div');
                empty.className = 'empty-tip';
                empty.textContent = '暂无消息，发条消息开始聊天吧';
                empty.style.cssText = 'padding:40px 0;text-align:center;color:var(--text-sub);';
                box.appendChild(empty);
            }

            // 打开会话即视为已读（单聊有效），并通知对方
            markCurrentRead();
        }).catch(function () {
            info.loading = false;
        });
    }

    /** 绑定聊天窗口滚动事件：滑到顶部自动加载更多历史消息 */
    function bindScrollMore(type, id) {
        var box = el('chat-messages');
        if (!box) return;
        // 移除旧监听（通过 clone 节点方式不现实，改用标记）
        box.onscroll = function () {
            var key = type + ':' + id;
            var info = pageInfo[key];
            if (!info || !info.hasMore || info.loading) return;
            // 滚动到距顶部 50px 以内时触发加载
            if (box.scrollTop < 50) {
                loadHistory(type, id, info.page + 1);
            }
        };
    }

    /** 标记当前单聊会话为已读（群聊不展示已读回执，跳过） */
    function markCurrentRead() {
        if (!App.current || App.current.type !== 'USER') return;
        Api.read(App.current.type, App.current.id);
    }

    function bubbleNode(wm) {
        // 语音通话记录：居中灰色胶囊，不区分左右气泡、不显示头像 / 撤回 / 删除
        if (wm.type === 'CALL') {
            const wrap = document.createElement('div');
            wrap.className = 'msg call-record';
            wrap.setAttribute('data-mid', wm.id);
            wrap.innerHTML = callRecordInner(wm);
            return wrap;
        }
        const me = wm.senderId === App.user.id;
        const wrap = document.createElement('div');
        wrap.className = 'msg' + (me ? ' me' : '') + (wm.urgent ? ' urgent' : '');
        wrap.setAttribute('data-mid', wm.id);
        const tag = wm.urgent ? '<div class="urgent-tag">⚡ 加急</div>' : '';
        let bodyHtml;
        if (wm.type === 'IMAGE') {
            bodyHtml = '<img src="' + wm.content + '" onclick="App.previewImage(\'' + wm.content.replace(/'/g, "\\'") + '\')">';
        } else if (wm.type === 'VOICE') {
            bodyHtml = voiceBubbleInner(wm.content);
        } else {
            bodyHtml = App.escapeHtml(wm.content);
        }
        // ⑨ 消息炸弹：仍在倒计时中时，气泡下方挂一个环形倒计时
        const bombTag = (wm.bombStatus === 'PENDING' && wm.bombDeadline)
            ? '<div class="bomb-ring" data-deadline="' + wm.bombDeadline + '">💣 <span class="bomb-left">--</span>s</div>'
            : '';
        const inner = '<div class="bubble' + (wm.type === 'VOICE' ? ' voice' : '') + '">' + tag + bodyHtml + bombTag + '</div>';
        // 群聊中若发送者是我的好友，气泡名优先显示备注
        const senderName = App.friendName(wm.senderId, wm.senderNickname);
        wrap.innerHTML = App.avatarHtml({ id: wm.senderId, nickname: senderName, avatar: wm.senderAvatar }) + inner;
        // 自己发送且仍在撤回窗口内，显示「撤回」
        if (me && !wm.recalled && withinRecallWindow(wm)) {
            const btn = document.createElement('button');
            btn.className = 'msg-recall';
            btn.textContent = '撤回';
            btn.onclick = function (e) {
                e.stopPropagation();
                recallMessage(wm.id);
            };
            wrap.appendChild(btn);
        }
        // ⑨ 炸弹倒计时：气泡渲染完成后启动秒级刷新
        if (bombTag) startBombTicker(wrap);
        // 自己的消息且已被对方已读：显示「已读」回执
        if (me && wm.read) {
            markReadFlag(wrap);
        }
        // 删除消息按钮（hover 显示）
        const delBtn = document.createElement('button');
        delBtn.className = 'msg-del';
        delBtn.textContent = '✕';
        delBtn.title = '删除此消息';
        delBtn.onclick = async function (e) {
            e.stopPropagation();
            if (!await App.confirm('确定删除此消息？', { danger: true })) return;
            Api.deleteMessage(wm.id).then(function (d) {
                if (d && d.code === 0) {
                    // 从聊天框移除
                    var node = el('chat-messages').querySelector('[data-mid="' + wm.id + '"]');
                    if (node) {
                        // 同时移除前面的时间分隔（如果有）
                        var prev = node.previousElementSibling;
                        if (prev && prev.classList.contains('msg-time')) prev.remove();
                        node.remove();
                    }
                    App.notify('已删除');
                    // 立即让侧边列表「最后一条」预览也反映删除（后端已过滤已删除消息）
                    Chat.refreshConversations();
                } else {
                    App.notify((d && d.message) || '删除失败');
                }
            });
        };
        wrap.appendChild(delBtn);
        return wrap;
    }

    /** 在气泡后追加「已读」回执（仅对自己的消息） */
    function markReadFlag(node) {
        if (!node || !node.classList.contains('me') || node.querySelector('.read-flag')) return;
        const f = document.createElement('div');
        f.className = 'read-flag';
        f.textContent = '已读';
        node.appendChild(f);
    }

    /** moyo 助手「正在输入」动画气泡：仅在与助手对话时显示/隐藏 */
    let assistantTypingNode = null;
    function showAssistantTyping(on) {
        const box = el('chat-messages');
        if (!box) return;
        if (!on) {
            if (assistantTypingNode) { assistantTypingNode.remove(); assistantTypingNode = null; }
            return;
        }
        if (assistantTypingNode) return; // 已有
        assistantTypingNode = document.createElement('div');
        assistantTypingNode.className = 'msg assistant-typing';
        assistantTypingNode.innerHTML = '<div class="bubble typing"><span></span><span></span><span></span></div>';
        box.appendChild(assistantTypingNode);
        scrollToBottom(box, true);
    }

    function appendBubble(wm, scroll) {
        const box = el('chat-messages');
        if (!box) return;
        // 双保险：已被软删除的消息绝不渲染（后端查询已过滤，这里再兜一层）
        if (wm && wm.deleted === true) return;
        // 去重：如果已有相同 data-mid 的气泡，不重复追加（防止 WebSocket 回执与乐观插入重复）
        if (wm.id && box.querySelector('[data-mid="' + wm.id + '"]')) {
            return;
        }
        if (wm.recalled) {
            const tip = document.createElement('div');
            tip.className = 'sys-tip';
            tip.setAttribute('data-mid', wm.id);
            const who = wm.senderId === App.user.id ? '你' : (wm.senderNickname || (App.current ? App.current.name : '对方'));
            tip.textContent = who + ' 撤回了一条消息';
            box.appendChild(tip);
            if (scroll !== false) scrollToBottom(box, true);
            return;
        }
        const t = document.createElement('div');
        t.className = 'msg-time';
        t.textContent = App.msgTime(wm.createTime);
        box.appendChild(t);
        box.appendChild(bubbleNode(wm));
        if (scroll !== false) scrollToBottom(box, true);
    }

    function belongsToCurrent(wm) {
        const cur = App.current;
        if (!cur) return false;
        if (cur.type === 'USER') {
            const partner = cur.id;
            return wm.targetType === 'USER' &&
                ((wm.senderId === App.user.id && wm.targetId === partner) ||
                 (wm.senderId === partner && wm.targetId === App.user.id));
        }
        if (cur.type === 'GROUP') {
            return wm.targetType === 'GROUP' && wm.targetId === cur.id;
        }
        return false;
    }

    function conversationKeyOf(wm) {
        if (wm.targetType === 'USER') {
            const partner = wm.senderId === App.user.id ? wm.targetId : wm.senderId;
            return 'USER:' + partner;
        }
        return 'GROUP:' + wm.targetId;
    }

    function onIncoming(wm) {
        if (wm && wm.type === 'RECALL') {
            replaceWithRecalled(wm);
            return;
        }
        // moyo 助手「正在输入」提示：仅在当前正与助手对话时显示打字气泡
        if (wm && wm.type === 'TYPING' && App.current && App.current.type === 'USER'
                && wm.senderId === App.current.id && wm.targetId === App.user.id) {
            showAssistantTyping(true);
            return;
        }
        // 助手回复异常：直接在对话中提示
        if (wm && wm.type === 'ASSISTANT_ERROR' && App.current && App.current.type === 'USER'
                && wm.senderId === App.current.id && wm.targetId === App.user.id) {
            showAssistantTyping(false);
            const fake = { id: 'err-' + Date.now(), senderId: wm.senderId, type: 'TEXT', content: wm.content || 'moyo助手暂时无法回复', targetType: 'USER', targetId: App.user.id, urgent: false };
            appendBubble(fake, true);
            return;
        }
        if (wm && wm.type === 'SEND_FAILED') {
            // 自己发的消息被拦截（如被拉黑）：标记当前会话所有「发送中」气泡为失败
            const box = el('chat-messages');
            box.querySelectorAll('.msg[data-status="sending"]').forEach(function (n) { markFailed(n.getAttribute('data-mid')); });
            App.notify((wm.reason || '消息发送失败'));
            return;
        }
        // 收到自己消息的正常回执：移除本地「发送中」乐观气泡（精准匹配，避免误删其他发送中气泡）
        if (wm && wm.senderId === App.user.id) {
            // 先检查是否已有真实 ID 的气泡（去重），如果有说明是重复回执，不需要处理乐观气泡
            var existing = el('chat-messages').querySelector('[data-mid="' + wm.id + '"]');
            if (existing) {
                // 已存在相同 ID 的气泡，说明已处理过，仅更新会话列表
                var key0 = conversationKeyOf(wm);
                var conv0 = App.conversations.find(function (c) { return c.type + ':' + c.id === key0; });
                if (conv0) conv0.lastMessage = wm;
                if (App.activeTab === 'chat') renderConversations();
                updateTabBadge();
                return;
            }
            // 移除所有发送中气泡（回执到达，乐观气泡可被替换）
            el('chat-messages').querySelectorAll('.msg[data-status="sending"]').forEach(function (n) { n.remove(); });
        }
        // 更新会话列表最后一条 & 未读
        const key = conversationKeyOf(wm);
        const conv = App.conversations.find(function (c) { return c.type + ':' + c.id === key; });
        if (conv) {
            conv.lastMessage = wm;
            if (!belongsToCurrent(wm)) conv.unread = (conv.unread || 0) + 1;
        }
        if (belongsToCurrent(wm)) {
            appendBubble(wm, true);
            // 正在查看该会话，且是别人发来的消息：立即标记为已读并通知对方
            if (wm.senderId !== App.user.id) markCurrentRead();
        }
        // 收到加急消息：在对方界面弹出加急弹窗（已被屏蔽的发送方不弹）
        if (wm.urgent && wm.senderId !== App.user.id) {
            onUrgent(wm);
        }
        if (App.activeTab === 'chat') renderConversations();
        updateTabBadge();
    }

    /** 收到对方「已读」通知：将对应气泡标记为已读 */
    function onRead(wm) {
        const ids = wm.readIds || [];
        ids.forEach(function (id) {
            const node = el('chat-messages').querySelector('[data-mid="' + id + '"]');
            markReadFlag(node);
        });
        // 同步会话列表最后一条消息的已读状态
        if (App.current) {
            const conv = App.conversations.find(function (c) {
                return c.type + ':' + c.id === App.current.type + ':' + App.current.id;
            });
            if (conv && conv.lastMessage && ids.indexOf(conv.lastMessage.id) >= 0) {
                conv.lastMessage.read = true;
            }
        }
        // 刷新会话列表，使「已读」标记实时呈现
        if (App.activeTab === 'chat') renderConversations();
    }

    /* ===== 加急消息弹窗 ===== */
    let urgentModalOpen = false;
    let currentUrgentWm = null;
    let currentUrgentOverlay = null;

    /** 收到加急消息时，在对方界面弹出加急弹窗（已屏蔽该发送方则不弹） */
    function onUrgent(wm) {
        if (urgentModalOpen) return;                                  // 同一时间只弹一个，避免叠加
        if (isPeerMuted(wm.senderId)) return;    // 已屏蔽此发送方加急
        const name = wm.senderNickname || (App.current ? App.current.name : '对方');
        const typeLabel = wm.targetType === 'GROUP' ? '群聊' : '好友';
        const content = wm.type === 'IMAGE' ? '[图片]'
            : (wm.type === 'VOICE' ? '[语音]' : (wm.content || ''));
        const html =
            '<h3>⚡ 加急消息</h3>' +
            '<div class="urgent-modal-from">来自：' + App.escapeHtml(name) + '（' + typeLabel + '）</div>' +
            '<div class="urgent-modal-content">' + App.escapeHtml(content) + '</div>' +
            '<div class="modal-actions">' +
            '  <button class="ghost" onclick="Chat.ignoreUrgent()">忽略</button>' +
            '  <button class="warn" onclick="Chat.muteUrgent(' + wm.senderId + ')">屏蔽</button>' +
            '  <button class="primary" onclick="Chat.viewUrgent()">查看</button>' +
            '</div>';
        const overlay = App.modal(html, function (ov) {
            ov.classList.add('urgent-modal-overlay');
            // 点击遮罩关闭时也同步清理状态
            ov.onclick = function (e) { if (e.target === ov) closeUrgentModal(); };
        });
        urgentModalOpen = true;
        currentUrgentWm = wm;
        currentUrgentOverlay = overlay;
    }

    function closeUrgentModal() {
        if (currentUrgentOverlay) currentUrgentOverlay.remove();
        currentUrgentOverlay = null;
        currentUrgentWm = null;
        urgentModalOpen = false;
    }

    /** 查看：打开对应会话（单聊定位到对方，群聊定位到群） */
    function viewUrgent() {
        const wm = currentUrgentWm;
        closeUrgentModal();
        if (!wm) return;
        if (wm.targetType === 'USER') {
            const partner = wm.senderId === App.user.id ? wm.targetId : wm.senderId;
            openConversation('USER', partner, wm.senderNickname || (App.current ? App.current.name : '对方'));
        } else {
            openConversation('GROUP', wm.targetId, wm.senderNickname || '群聊');
        }
    }

    /** 忽略：仅关闭弹窗，消息仍保留在会话中 */
    function ignoreUrgent() {
        closeUrgentModal();
    }

    /** 屏蔽：记录该发送方，之后其加急消息不再弹窗，并关闭弹窗 */
    function muteUrgent(peerId) {
        Api.urgentMute(peerId, true).then(function (d) {
            if (d && d.code === 0) {
                App.notify('已屏蔽该联系人的加急消息');
                // 以服务端权威状态刷新前端（保证与数据库一致）
                reloadMutedPeers(function () {
                    if (App.current && App.current.type === 'USER' && App.current.id === peerId) {
                        renderChatHeader('USER', peerId, App.current.name);
                    }
                });
            } else {
                App.notify((d && d.message) || '屏蔽失败');
            }
        });
        closeUrgentModal();
    }

    /** 在单聊窗口头部切换屏蔽状态 */
    function toggleUrgentMute() {
        const cur = App.current;
        if (!cur || cur.type !== 'USER') return;
        const peerId = cur.id;
        const willMute = !isPeerMuted(peerId);
        Api.urgentMute(peerId, willMute).then(function (d) {
            if (d && d.code === 0) {
                App.notify(willMute
                    ? ('已屏蔽 ' + cur.name + ' 的加急消息')
                    : ('已取消屏蔽 ' + cur.name + ' 的加急消息'));
                // 关键：从服务端重新拉取权威列表重建前端状态，
                // 确保「取消屏蔽」后界面与数据库完全一致（避免本地数组残留导致按钮不翻转）
                reloadMutedPeers(function () {
                    renderChatHeader('USER', peerId, cur.name);
                });
            } else {
                App.notify((d && d.message) || '操作失败');
            }
        });
    }

    function sendText() {
        const ta = el('msg-input');
        const content = ta.value.trim();
        if (!content || !App.current) return;
        const urgent = !!App.urgentMode;
        if (urgent) {
            if (Date.now() < App.urgentCooldownUntil) {
                App.notify('加急消息需间隔 10 秒，请稍后再试');
                return; // 保留输入内容，不发送
            }
        }
        ta.value = '';
        const tmpId = 'tmp-' + Date.now();
        const bombSeconds = App.bombArmed ? DEFAULT_BOMB_SECONDS : null;
        appendLocalBubble('TEXT', content, tmpId, 'sending', urgent);
        doSend({ senderId: App.user.id, type: 'TEXT', content: content, targetType: App.current.type, targetId: App.current.id, urgent: urgent, bombSeconds: bombSeconds }, tmpId);
        if (urgent) startUrgentCooldown();
        resetUrgent();
        if (bombSeconds) resetBomb();
        sendTypingIfUser(false); // 发送后结束「正在输入」状态
        sendTypingContent('');    // 清空对方向我窥探的面板内容
    }

    function sendImage(input) {
        const file = input.files && input.files[0];
        if (!file || !App.current) { input.value = ''; return; }
        const urgent = !!App.urgentMode;
        if (urgent && Date.now() < App.urgentCooldownUntil) {
            App.notify('加急消息需间隔 10 秒，请稍后再试');
            input.value = '';
            return;
        }
        const tmpId = 'tmp-' + Date.now();
        Api.upload(file, 'photo/chat').then(function (data) {
            if (!data || data.code !== 0 || !data.data || !data.data.url) {
                App.notify((data && data.message) || '图片上传失败');
                input.value = '';
                return;
            }
            appendLocalBubble('IMAGE', data.data.url, tmpId, 'sending', urgent);
            doSend({ senderId: App.user.id, type: 'IMAGE', content: data.data.url, targetType: App.current.type, targetId: App.current.id, urgent: urgent }, tmpId);
            if (urgent) startUrgentCooldown();
            resetUrgent();
            sendTypingIfUser(false);
            input.value = '';
        }).catch(function () {
            App.notify('图片上传失败');
            input.value = '';
        });
    }

    /* ===== 语音消息（与图片走同一上传链路，仅类型 VOICE） ===== */
    // 按住录音（MediaRecorder）；浏览器不支持 / 不授权麦克风时，回退为选择音频文件
    let voiceMode = false; // true=语音输入模式（按住说话），false=文字输入模式
    let voiceRecorder = null;
    let voiceChunks = [];
    let voiceStream = null;
    let voiceStartTs = 0;
    let voiceCancel = false;
    let voicePanelTimer = null;

    function voiceRecordingSupported() {
        return !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia && window.MediaRecorder);
    }

    /** 语音入口：选择音频文件时触发（回退路径） */
    function sendVoice(input) {
        const file = input.files && input.files[0];
        if (!file || !App.current) { input.value = ''; return; }
        uploadAndSendVoice(file);
        input.value = '';
    }

    /** 按住说话：指针按下开始录音 */
    function voicePointerDown(e) {
        if (!App.current) return;
        if (!voiceRecordingSupported()) {
            el('voice-input').click();
            return;
        }
        e.preventDefault();
        try { e.target.setPointerCapture(e.pointerId); } catch (err) {}
        voiceCancel = false;
        navigator.mediaDevices.getUserMedia({ audio: true }).then(function (stream) {
            voiceStream = stream;
            voiceChunks = [];
            let mr;
            try { mr = new MediaRecorder(stream); } catch (err) { fallbackVoiceFile(); return; }
            voiceRecorder = mr;
            mr.ondataavailable = function (ev) { if (ev.data && ev.data.size) voiceChunks.push(ev.data); };
            mr.onstop = function () {
                stopVoiceTracks();
                const dur = Date.now() - voiceStartTs;
                if (voiceCancel || dur < 1000) { hideVoicePanel(); return; }
                const blob = new Blob(voiceChunks, { type: (mr.mimeType || 'audio/webm') });
                hideVoicePanel();
                uploadAndSendVoice(blob);
            };
            voiceStartTs = Date.now();
            try { mr.start(); } catch (err) { stopVoiceTracks(); fallbackVoiceFile(); return; }
            showVoicePanel();
        }).catch(function () {
            App.notify('无法访问麦克风，已切换为选择语音文件');
            fallbackVoiceFile();
        });
    }

    /** 指针移动：上滑超过阈值视为取消 */
    function voicePointerMove(e) {
        if (!voiceRecorder || voiceRecorder.state === 'inactive') return;
        const btn = el('btn-hold');
        if (!btn) return;
        const rect = btn.getBoundingClientRect();
        voiceCancel = (e.clientY < rect.top - 40);
        const hint = el('voice-panel-hint');
        if (hint) hint.textContent = voiceCancel ? '松开取消' : '松开发送';
    }

    /** 指针抬起：发送或取消 */
    function voicePointerUp() {
        if (voiceRecorder && voiceRecorder.state !== 'inactive') voiceRecorder.stop();
    }

    function stopVoiceTracks() {
        if (voiceStream) {
            voiceStream.getTracks().forEach(function (t) { t.stop(); });
            voiceStream = null;
        }
    }

    function fallbackVoiceFile() {
        hideVoicePanel();
        if (el('voice-input')) el('voice-input').click();
    }

    /** 上传语音文件 / Blob 并发送 VOICE 消息（与图片共用 /api/file/upload 链路） */
    function uploadAndSendVoice(fileOrBlob) {
        if (!App.current) return;
        const urgent = !!App.urgentMode;
        if (urgent && Date.now() < App.urgentCooldownUntil) {
            App.notify('加急消息需间隔 10 秒，请稍后再试');
            return;
        }
        const tmpId = 'tmp-' + Date.now();
        Api.upload(fileOrBlob, 'voice/chat').then(function (data) {
            if (!data || data.code !== 0 || !data.data || !data.data.url) {
                App.notify((data && data.message) || '语音上传失败');
                return;
            }
            appendLocalBubble('VOICE', data.data.url, tmpId, 'sending', urgent);
            doSend({ senderId: App.user.id, type: 'VOICE', content: data.data.url, targetType: App.current.type, targetId: App.current.id, urgent: urgent }, tmpId);
            if (urgent) startUrgentCooldown();
            resetUrgent();
        }).catch(function () {
            App.notify('语音上传失败');
        });
    }

    /** 录音浮层（波形动画 + 计时 + 提示） */
    function showVoicePanel() {
        hideVoicePanel();
        const p = document.createElement('div');
        p.className = 'voice-panel';
        p.id = 'voice-panel';
        p.innerHTML = '<div class="voice-wave"><span></span><span></span><span></span><span></span><span></span></div>' +
            '<div class="voice-time" id="voice-panel-time">0″</div>' +
            '<div class="voice-hint" id="voice-panel-hint">松开发送 · 上滑取消</div>';
        document.body.appendChild(p);
        const hb = el('btn-hold');
        if (hb) hb.classList.add('recording');
        let sec = 0;
        const tEl = el('voice-panel-time');
        voicePanelTimer = setInterval(function () {
            sec++;
            if (tEl) tEl.textContent = sec + '″';
            if (sec >= 60) { voiceCancel = true; voicePointerUp(); } // 最长 60 秒自动停止
        }, 1000);
    }

    function hideVoicePanel() {
        if (voicePanelTimer) { clearInterval(voicePanelTimer); voicePanelTimer = null; }
        const p = el('voice-panel');
        if (p) p.remove();
        const hb = el('btn-hold');
        if (hb) hb.classList.remove('recording');
    }

    /* ===== 语音气泡（仿微信：播放图标 + 时长，点击播放） ===== */
    function voiceBubbleInner(content) {
        const src = (content || '').replace(/"/g, '&quot;');
        return '<div class="v-wrap" onclick="Chat.toggleVoicePlay(this)">' +
            '<span class="v-play" title="播放">▶</span>' +
            '<span class="v-dur">0″</span>' +
            '<audio class="v-audio" preload="metadata" src="' + src + '" onloadedmetadata="Chat.fillVoiceDur(this)"></audio>' +
            '</div>';
    }

    function stopAllVoice() {
        document.querySelectorAll('.v-wrap.playing').forEach(function (w) {
            const a = w.querySelector('.v-audio');
            if (a && !a.paused) a.pause();
            w.classList.remove('playing');
            const p = w.querySelector('.v-play');
            if (p) p.textContent = '▶';
        });
    }

    function toggleVoicePlay(wrap) {
        const audio = wrap.querySelector('.v-audio');
        const play = wrap.querySelector('.v-play');
        if (!audio) return;
        if (audio.paused) {
            stopAllVoice();
            audio.play().catch(function () {});
            wrap.classList.add('playing');
            if (play) play.textContent = '⏸';
            audio.onended = function () { wrap.classList.remove('playing'); if (play) play.textContent = '▶'; };
        } else {
            audio.pause();
            wrap.classList.remove('playing');
            if (play) play.textContent = '▶';
        }
    }

    function fillVoiceDur(audioEl) {
        const wrap = audioEl.closest ? audioEl.closest('.v-wrap') : null;
        if (!wrap) return;
        const d = wrap.querySelector('.v-dur');
        if (!d) return;
        const sec = Math.round(audioEl.duration || 0);
        d.textContent = sec + '″';
    }

    /* ===== 功能面板（＋ 号，可拓展图片 / 文件等） ===== */
    function pickImage() { const i = el('img-input'); if (i) i.click(); closeMorePanel(); }
    function pickFile() { const i = el('file-input'); if (i) i.click(); closeMorePanel(); }
    function onFilePicked(input) {
        const f = input.files && input.files[0];
        input.value = '';
        if (!f) return;
        App.notify('文件消息类型即将上线，敬请期待');
    }

    function toggleMorePanel() { const p = el('more-panel'); if (p) p.classList.toggle('hidden'); }
    function closeMorePanel() { const p = el('more-panel'); if (p) p.classList.add('hidden'); }

    function onMoreClick() {
        toggleMorePanel();
    }

    function onComposerInput() {
        /* 发送按钮已独立存在，加号固定为「更多功能」入口，无需再把加号变发送 */
    }

    /** 切换 语音 / 文字 输入模式：整个输入框在「文字框」与「按住说话」之间切换 */
    function toggleVoiceMode() {
        if (!App.current) return;
        voiceMode = !voiceMode;
        applyVoiceMode();
    }

    /** 应用当前输入模式：显示文字框或「按住说话」按钮，并同步语音按钮高亮与发送按钮可用态 */
    function applyVoiceMode() {
        const ta = el('msg-input');
        const hold = el('btn-hold');
        const vb = el('btn-voice');
        const send = el('btn-send');
        if (!ta || !hold) return;
        // 关键：统一用 .hidden 类（display:none !important）切换，避免 HTML hidden 属性被 UA 规则隐藏后无法解除
        ta.classList.toggle('hidden', voiceMode);        // 语音模式隐藏文字框
        hold.classList.toggle('hidden', !voiceMode);     // 文字模式隐藏「按住说话」
        if (vb) vb.classList.toggle('active', voiceMode);
        if (send) send.disabled = !!(voiceMode || (ta.disabled));
    }

    function onComposerKey(e) {
        if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendText(); }
    }

    // 点击面板 / 按钮以外区域自动收起功能面板
    document.addEventListener('click', function (e) {
        const p = el('more-panel');
        if (!p || p.classList.contains('hidden')) return;
        if (p.contains(e.target)) return;
        const mb = el('btn-more');
        if (mb && mb.contains(e.target)) return;
        closeMorePanel();
    });

    /** 消息菜单统一搜索（仿微信）：一个搜索框融合「搜账号」+「搜聊天记录」。
     *  空输入 → 恢复会话列表；有关键字 → 并行查用户与聊天记录，分两段渲染到侧边栏。 */
    function renderUnifiedSearch(kw) {
        kw = (kw || '').trim();
        const list = el('side-list');
        if (!list) return;
        if (!kw) {
            renderConversations(); // 清空搜索，恢复会话列表
            return;
        }
        list.innerHTML = '<div class="empty-tip">搜索中…</div>';

        const userReq = Api.userSearch(kw);
        const msgReq = Api.msgSearch(kw, '', '', 0, 20);

        Promise.all([userReq, msgReq]).then(function (res) {
            const users = (res[0] && res[0].code === 0 && res[0].data) ? res[0].data : [];
            const msgs = (res[1] && res[1].code === 0 && res[1].data) ? res[1].data : [];
            list.innerHTML = '';

            // 联系人
            const uTitle = document.createElement('div');
            uTitle.className = 'section-title';
            uTitle.textContent = '联系人';
            list.appendChild(uTitle);
            if (!users.length) {
                list.insertAdjacentHTML('beforeend', '<div class="empty-tip">无匹配用户</div>');
            }
            users.forEach(function (u) {
                const isSelf = (App.user && u.id === App.user.id);
                const isFriend = !!u.isFriend;
                const item = document.createElement('div');
                item.className = 'item';
                const dispName = App.friendName(u.id, u.nickname || u.username);
                item.innerHTML = App.entityClickable(u, 'USER', u.id) +
                    '<div class="meta"><div class="name">' + App.escapeHtml(dispName) + '</div>' +
                    '<div class="sub">账号 ' + App.escapeHtml(u.account || '') + ' · @' + App.escapeHtml(u.username) + '</div></div>';
                if (isSelf) {
                    const tag = document.createElement('span');
                    tag.className = 'sub';
                    tag.textContent = '我自己';
                    item.appendChild(tag);
                } else if (isFriend) {
                    const tag = document.createElement('span');
                    tag.className = 'sub';
                    tag.textContent = '已是好友';
                    item.appendChild(tag);
                } else {
                    const btn = document.createElement('button');
                    btn.className = 'op add';
                    btn.textContent = '加好友';
                    btn.onclick = function (e) {
                        e.stopPropagation();
                        Api.friendApply(u.id).then(function (d) {
                            App.notify(d && d.code === 0 ? '好友申请已发送' : (d && d.message));
                            if (d && d.code === 0) { btn.textContent = '已发送'; btn.disabled = true; }
                        });
                    };
                    item.appendChild(btn);
                }
                item.onclick = function () {
                    if (!isSelf) Chat.openConversation('USER', u.id, u.nickname || u.username);
                };
                list.appendChild(item);
            });

            // 聊天记录
            const mTitle = document.createElement('div');
            mTitle.className = 'section-title';
            mTitle.textContent = '聊天记录';
            list.appendChild(mTitle);
            if (!msgs.length) {
                list.insertAdjacentHTML('beforeend', '<div class="empty-tip">没有找到「' + App.escapeHtml(kw) + '」相关的聊天记录</div>');
            }
            msgs.forEach(function (it) {
                const m = it.message;
                const snippet = m.type === 'VOICE' ? '[语音]'
                    : (m.recalled ? '[撤回的消息]' : highlight(m.content, kw));
                const item = document.createElement('div');
                item.className = 'item sr-side-item';
                item.innerHTML = App.avatarHtml({ id: 0, name: it.convName, avatar: '' }) +
                    '<div class="meta"><div class="name">' + App.escapeHtml(it.convName) + '</div>' +
                    '<div class="sub">' + App.escapeHtml(m.senderNickname || '') + '：' + snippet + '</div></div>';
                item.onclick = function () {
                    Chat.openConversation(it.convType, it.convId, it.convName);
                    setTimeout(function () { Chat.jumpToMessage(it.convType, it.convId, it.convName, m.id); }, 300);
                };
                list.appendChild(item);
            });
        }).catch(function () {
            list.innerHTML = '<div class="empty-tip">搜索失败，请重试</div>';
        });
    }

    /* ===== 聊天记录搜索与管理（仿微信） ===== */
    /** 转义后高亮关键字 */
    function highlight(text, kw) {
        const safe = App.escapeHtml(text || '');
        if (!kw) return safe;
        const lower = safe.toLowerCase();
        const kl = kw.toLowerCase();
        let out = '', i = 0, idx;
        while ((idx = lower.indexOf(kl, i)) !== -1) {
            out += safe.substring(i, idx) + '<mark>' + safe.substring(idx, idx + kl.length) + '</mark>';
            i = idx + kl.length;
        }
        out += safe.substring(i);
        return out;
    }

    /** 打开聊天记录搜索弹窗 */
    function openSearch() {
        const hasConv = !!(App.current && App.current.type);
        const scopeHtml = hasConv
            ? '<label class="sr-scope"><input type="radio" name="sr-scope" value="all" checked> 全部聊天</label>' +
              '<label class="sr-scope"><input type="radio" name="sr-scope" value="current"> 当前会话（' + App.escapeHtml(App.current.name || '') + '）</label>'
            : '<label class="sr-scope"><input type="radio" name="sr-scope" value="all" checked> 全部聊天</label>';
        const html =
            '<div class="sr-head">' +
            '  <input id="sr-kw" class="sr-input" placeholder="输入关键字搜索聊天记录">' +
            '  <button class="primary" onclick="Chat.runSearch(0)">搜索</button>' +
            '</div>' +
            '<div class="sr-scope-row">' + scopeHtml + '</div>' +
            '<div class="sr-list" id="sr-list"><div class="sr-empty">输入关键字，查找你与好友 / 群聊的聊天记录</div></div>';
        const overlay = App.modal(html, function (ov) {
            const inp = ov.querySelector('#sr-kw');
            if (inp) {
                inp.addEventListener('keydown', function (e) { if (e.key === 'Enter') Chat.runSearch(0); });
                setTimeout(function () { inp.focus(); }, 30);
            }
        }, 'search');
        overlay.classList.add('search-modal-overlay');
    }

    /** 执行搜索并渲染结果（page 用于「加载更多」） */
    function runSearch(page) {
        const kw = (el('sr-kw') ? el('sr-kw').value : '').trim();
        if (!kw) { App.notify('请输入关键字'); return; }
        const scopeEl = document.querySelector('input[name="sr-scope"]:checked');
        const scope = scopeEl ? scopeEl.value : 'all';
        let targetType = null, targetId = null;
        if (scope === 'current' && App.current) {
            targetType = App.current.type;
            targetId = App.current.id;
        }
        const list = el('sr-list');
        if (page === 0) list.innerHTML = '<div class="sr-loading">搜索中…</div>';
        Api.msgSearch(kw, targetType, targetId, page || 0, 30).then(function (d) {
            if (!d || d.code !== 0 || !Array.isArray(d.data)) {
                list.innerHTML = '<div class="sr-empty">搜索失败</div>';
                return;
            }
            if (page === 0 && d.data.length === 0) {
                list.innerHTML = '<div class="sr-empty">没有找到包含「' + App.escapeHtml(kw) + '」的聊天记录</div>';
                return;
            }
            if (page === 0) list.innerHTML = '';
            d.data.forEach(function (it) {
                const m = it.message;
                const item = document.createElement('div');
                item.className = 'sr-item';
                const icon = it.convType === 'GROUP' ? '👥' : '👤';
                const snippet = m.type === 'VOICE' ? '[语音]'
                    : (m.recalled ? '[撤回的消息]' : highlight(m.content, kw));
                item.innerHTML =
                    '<div class="sr-item-head">' +
                    '  <span class="sr-conv">' + icon + ' ' + App.escapeHtml(it.convName) + '</span>' +
                    '  <span class="sr-meta">' + App.escapeHtml(m.senderNickname || '') + ' · ' + App.escapeHtml(App.msgTime(m.createTime)) + '</span>' +
                    '</div>' +
                    '<div class="sr-content">' + snippet + '</div>' +
                    '<button class="sr-del" title="删除这条消息" onclick="Chat.deleteSearchResult(' + m.id + ', this)">删除</button>';
                item.onclick = function (e) {
                    if (e.target.classList.contains('sr-del')) return;
                    Chat.jumpToMessage(it.convType, it.convId, it.convName, m.id);
                    const ov = document.querySelector('.search-modal-overlay');
                    if (ov) ov.remove();
                };
                list.appendChild(item);
            });
            if (d.data.length >= 30) {
                const more = document.createElement('button');
                more.className = 'sr-more';
                more.textContent = '加载更多';
                more.onclick = function () { Chat.runSearch((page || 0) + 1); };
                list.appendChild(more);
            }
        });
    }

    /** 从搜索结果中删除一条消息 */
    function deleteSearchResult(messageId, btn) {
        Api.deleteMessage(messageId).then(function (d) {
            if (d && d.code === 0) {
                const item = btn.closest('.sr-item');
                if (item) item.remove();
                App.notify('已删除');
                Chat.refreshConversations();
            } else {
                App.notify((d && d.message) || '删除失败');
            }
        });
    }

    /** 跳转到某条消息所在会话并定位（逐页加载历史直到命中） */
    async function jumpToMessage(type, id, name, messageId) {
        openConversation(type, id, name);
        await new Promise(function (r) { setTimeout(r, 350); });
        const box = el('chat-messages');
        if (!box) return;
        let page = 0;
        while (page < 60) {
            if (box.querySelector('[data-mid="' + messageId + '"]')) break;
            page++;
            let data;
            try { data = await Api.history(type, id, page, 30); } catch (e) { break; }
            if (!data || data.code !== 0) break;
            const items = (data.data && data.data.items) ? data.data.items : (Array.isArray(data.data) ? data.data : []);
            if (!items.length) break;
            const prevScrollHeight = box.scrollHeight;
            const prevScrollTop = box.scrollTop;
            const frag = document.createDocumentFragment();
            items.forEach(function (wm) {
                const t = document.createElement('div');
                t.className = 'msg-time';
                t.textContent = App.msgTime(wm.createTime);
                frag.appendChild(t);
                frag.appendChild(bubbleNode(wm));
            });
            box.insertBefore(frag, box.firstChild);
            box.scrollTop = prevScrollTop + (box.scrollHeight - prevScrollHeight);
            if (data.data.hasMore === false) break;
            if (items.length < 30) break;
        }
        const node = box.querySelector('[data-mid="' + messageId + '"]');
        if (node) {
            node.scrollIntoView({ block: 'center' });
            node.classList.add('flash');
            setTimeout(function () { node.classList.remove('flash'); }, 2200);
        }
    }

    /** 收到对方「正在输入」状态：记录并刷新界面（带超时自动清除，防止对方失联后一直显示） */
    let typingTimers = {};
    function onTyping(wm) {
        if (!wm || wm.targetType !== 'USER') return;
        const partner = wm.senderId;            // 正在输入的对方
        App.typing[partner] = !!wm.typing;
        if (typingTimers[partner]) clearTimeout(typingTimers[partner]);
        if (wm.typing) {
            typingTimers[partner] = setTimeout(function () {
                App.typing[partner] = false;
                renderTyping();
                if (App.activeTab === 'chat') renderConversations();
            }, 6000);
        }
        renderTyping();
        if (App.activeTab === 'chat') renderConversations();
    }

    /** 在聊天区顶部显示/隐藏「对方正在输入中…」 + 「查看」按钮（窥探对方输入框） */
    function renderTyping() {
        const tip = el('typing-tip');
        if (!tip) return;
        const cur = App.current;
        if (cur && cur.type === 'USER' && App.typing[cur.id]) {
            const viewing = (App.liveViewId === cur.id);
            tip.innerHTML = (cur.name || '对方') + ' 正在输入中…' +
                (viewing ? '' : ' <button class="typing-view-btn" onclick="Chat.openLiveView(' + cur.id + ')">查看</button>');
            tip.classList.add('show');
        } else {
            tip.textContent = '';
            tip.classList.remove('show');
        }
    }

    /** 仅在单聊时向对方推送输入状态 */
    function sendTypingIfUser(typing) {
        if (!App.current || App.current.type !== 'USER') return;
        Api.typing(App.current.type, App.current.id, typing);
    }

    /** 输入框获得焦点：通知对方「正在输入」 */
    function onInputFocus() {
        sendTypingIfUser(true);
    }

    /** 输入框失去焦点：通知对方「停止输入」 */
    function onInputBlur() {
        sendTypingIfUser(false);
    }

    /** 输入框内容变化：若对方正在「查看」我的输入框，则实时把内容推给对方（防抖 150ms） */
    let contentSendTimer = null;
    function onInput() {
        if (!App.current || App.current.type !== 'USER') return;
        const peer = App.current.id;
        if (!App.peersViewingMe[peer]) return; // 无人查看则不推送（隐私 + 省流量）
        if (contentSendTimer) clearTimeout(contentSendTimer);
        const ta = el('msg-input');
        contentSendTimer = setTimeout(function () {
            sendTypingContent(ta ? ta.value : '');
        }, 150);
    }

    /** 通过 WS 把当前输入框内容推送给对方（仅单聊、仅对方正在查看时调用） */
    function sendTypingContent(content) {
        if (!App.current || App.current.type !== 'USER') return;
        if (!App.peersViewingMe[App.current.id]) return;
        if (!Ws.client || !Ws.client.connected) return;
        Ws.client.send('/app/typing.content', {}, JSON.stringify({
            senderId: App.user.id,
            targetType: 'USER',
            targetId: App.current.id,
            content: content
        }));
    }

    /** 收到对方实时输入框内容：缓存并刷新窥探面板 */
    function onTypingContent(wm) {
        if (!wm || wm.targetType !== 'USER') return;
        const partner = wm.senderId;
        App.typingContent[partner] = wm.content || '';
        if (App.liveViewId === partner) updateLiveBody();
    }

    /** 收到对方「查看开关」：记录/清除正在查看我的对方 */
    function onTypingView(wm) {
        if (!wm || wm.targetType !== 'USER') return;
        const viewerId = wm.senderId;
        if (wm.typing) {
            App.peersViewingMe[viewerId] = true;
            // 新查看者加入时，若我正与该对方聊天且输入框聚焦，立即推一次当前内容
            if (App.current && App.current.type === 'USER' && App.current.id === viewerId) {
                const ta = el('msg-input');
                if (ta && document.activeElement === ta) sendTypingContent(ta.value);
            }
        } else {
            delete App.peersViewingMe[viewerId];
        }
    }

    /** 打开「窥探」面板：通知对方开始查看，并实时展示对方输入框内容 */
    function openLiveView(partnerId) {
        if (App.liveViewId) return; // 同时只窥探一个
        App.liveViewId = partnerId;
        if (Ws.client && Ws.client.connected) {
            Ws.client.send('/app/typing.view', {}, JSON.stringify({
                senderId: App.user.id,
                targetType: 'USER',
                targetId: partnerId,
                typing: true
            }));
        }
        const name = (App.current && App.current.name) || '对方';
        const panel = document.createElement('div');
        panel.className = 'live-view-panel';
        panel.id = 'live-view-panel';
        panel.innerHTML =
            '<div class="lv-head">👀 正在窥探 ' + App.escapeHtml(name) + ' 的输入' +
            '<button class="lv-close" onclick="Chat.closeLiveView()">✕</button></div>' +
            '<div class="lv-body" id="live-view-body"></div>';
        document.body.appendChild(panel);
        updateLiveBody();
        renderTyping(); // 隐藏「查看」按钮（已进入窥探）
    }

    /** 关闭窥探面板：通知对方停止查看 */
    function closeLiveView() {
        const partnerId = App.liveViewId;
        App.liveViewId = null;
        const panel = document.getElementById('live-view-panel');
        if (panel) panel.remove();
        if (partnerId && Ws.client && Ws.client.connected) {
            Ws.client.send('/app/typing.view', {}, JSON.stringify({
                senderId: App.user.id,
                targetType: 'USER',
                targetId: partnerId,
                typing: false
            }));
        }
        if (partnerId) delete App.typingContent[partnerId];
        renderTyping(); // 重新显示「查看」按钮
    }

    /** 刷新窥探面板内容 */
    function updateLiveBody() {
        const body = document.getElementById('live-view-body');
        if (!body) return;
        const content = App.typingContent[App.liveViewId] || '';
        body.textContent = content || '（对方输入框为空）';
        body.classList.toggle('empty', !content);
    }

    /** 切换「加急」模式（冷却期内不允许开启加急） */
    function toggleUrgent() {
        if (!App.urgentMode) {
            if (Date.now() < App.urgentCooldownUntil) {
                App.notify('加急消息需间隔 10 秒，请稍后再试');
                return;
            }
        }
        App.urgentMode = !App.urgentMode;
        updateUrgentBtn();
    }

    function updateUrgentBtn() {
        const b = el('btn-urgent');
        if (!b) return;
        const ico = b.querySelector('.ico');
        const cooling = Date.now() < App.urgentCooldownUntil;
        if (cooling) {
            const left = Math.ceil((App.urgentCooldownUntil - Date.now()) / 1000);
            if (ico) ico.textContent = '⚡' + left + 's';
            b.classList.add('cooling');
            b.classList.remove('active');
            b.disabled = true;
        } else {
            if (ico) ico.textContent = '⚡';
            b.classList.remove('cooling');
            b.classList.toggle('active', !!App.urgentMode);
            b.disabled = !!(el('msg-input') && el('msg-input').disabled);
        }
    }

    /** 发送加急成功后启动 10 秒冷却倒计时（按钮显示剩余秒数并禁用） */
    function startUrgentCooldown() {
        App.urgentCooldownUntil = Date.now() + 10000;
        updateUrgentBtn();
        const tick = function () {
            if (Date.now() >= App.urgentCooldownUntil) {
                updateUrgentBtn();
                return;
            }
            updateUrgentBtn();
            setTimeout(tick, 1000);
        };
        setTimeout(tick, 1000);
    }

    /** 发送后关闭加急模式（加急为单条消息属性） */
    function resetUrgent() {
        if (App.urgentMode) {
            App.urgentMode = false;
            updateUrgentBtn();
        }
    }

    /** ⑨ 切换「消息炸弹」：仅单聊可用，开启后下一条文本消息将挂上倒计时炸弹 */
    function toggleBomb() {
        if (!App.current || App.current.type !== 'USER') {
            App.notify('消息炸弹仅支持单聊文本消息');
            return;
        }
        App.bombArmed = !App.bombArmed;
        updateBombBtn();
        App.notify(App.bombArmed ? ('💣 已装填炸弹（' + DEFAULT_BOMB_SECONDS + ' 秒内对方须回复）') : '已取消炸弹');
    }

    function updateBombBtn() {
        const b = el('btn-bomb');
        if (!b) return;
        const canBomb = App.current && App.current.type === 'USER';
        b.disabled = !canBomb;
        b.classList.toggle('active', !!App.bombArmed);
    }

    /** 发送后关闭炸弹模式（炸弹为单条消息属性） */
    function resetBomb() {
        if (App.bombArmed) {
            App.bombArmed = false;
            updateBombBtn();
        }
    }

    /** 真正通过 WebSocket 发送；连接未建立则直接标记失败 */
    function doSend(dto, tmpId) {
        if (!window.Ws || !Ws.client || !Ws.client.connected) {
            markFailed(tmpId);
            return;
        }
        try {
            Ws.client.send('/app/chat.send', {}, JSON.stringify(dto));
        } catch (e) {
            markFailed(tmpId);
        }
    }

    /** 保存一条语音通话记录到会话（type=CALL，content 为 JSON {r:结果码, d:时长秒}）；服务端落库后双方都会收到回执并渲染 */
    function saveCallRecord(targetType, targetId, resultCode, duration) {
        if (!window.Ws || !Ws.client || !Ws.client.connected) return;
        const content = JSON.stringify({ r: resultCode || 'cancel', d: duration || 0 });
        const dto = { senderId: App.user.id, type: 'CALL', content: content, targetType: targetType, targetId: targetId, urgent: false };
        try { Ws.client.send('/app/chat.send', {}, JSON.stringify(dto)); } catch (e) {}
    }

    /** 本地乐观插入一条消息气泡（发送中 / 失败） */
    function appendLocalBubble(type, content, tmpId, status, urgent) {
        const box = el('chat-messages');
        const wrap = document.createElement('div');
        wrap.className = 'msg me' + (status === 'failed' ? ' failed' : '') + (urgent ? ' urgent' : '');
        wrap.setAttribute('data-mid', tmpId);
        wrap.setAttribute('data-status', status);
        wrap.setAttribute('data-content', (type === 'TEXT' || type === 'VOICE') ? content : '');
        wrap.setAttribute('data-type', type);
        const tag = urgent ? '<div class="urgent-tag">⚡ 加急</div>' : '';
        let bodyHtml;
        if (type === 'IMAGE') bodyHtml = '<img src="' + content + '">';
        else if (type === 'VOICE') bodyHtml = voiceBubbleInner(content);
        else bodyHtml = App.escapeHtml(content);
        const inner = '<div class="bubble' + (type === 'VOICE' ? ' voice' : '') + '">' + tag + bodyHtml + '</div>';
        wrap.innerHTML = App.avatarHtml({ id: App.user.id, nickname: App.user.nickname, avatar: App.user.avatar }) + inner;
        if (status === 'failed') {
            wrap.appendChild(buildFailTag(tmpId));
        }
        box.appendChild(wrap);
        scrollToBottom(box, true);
    }

    function buildFailTag(tmpId) {
        const fail = document.createElement('div');
        fail.className = 'send-fail';
        const tip = document.createElement('span');
        tip.textContent = '⚠ 发送失败';
        const retry = document.createElement('button');
        retry.className = 'retry-btn';
        retry.textContent = '重发';
        retry.onclick = function (e) { e.stopPropagation(); resend(tmpId); };
        fail.appendChild(tip);
        fail.appendChild(retry);
        return fail;
    }

    /** 把某条本地气泡标记为发送失败 */
    function markFailed(tmpId) {
        const node = el('chat-messages').querySelector('[data-mid="' + tmpId + '"]');
        if (!node) return;
        node.setAttribute('data-status', 'failed');
        node.classList.add('failed');
        if (!node.querySelector('.send-fail')) node.appendChild(buildFailTag(tmpId));
    }

    /** 重发 */
    function resend(tmpId) {
        const node = el('chat-messages').querySelector('[data-mid="' + tmpId + '"]');
        if (!node) return;
        const content = node.getAttribute('data-content');
        const type = node.getAttribute('data-type') || 'TEXT';
        if (!content) { App.notify(type === 'VOICE' ? '请重新录制语音发送' : '请重新选择图片发送'); return; }
        node.setAttribute('data-status', 'sending');
        node.classList.remove('failed');
        const fail = node.querySelector('.send-fail');
        if (fail) fail.remove();
        doSend({ senderId: App.user.id, type: type, content: content, targetType: App.current.type, targetId: App.current.id }, tmpId);
    }

    /** 消息正文缓存：messageId -> 最新文本。用于编辑时取准确原文（DOM 里的内容已被转义且混有角标） */
    const contentCache = {};

    /** ⑨ 炸弹倒计时：对单个气泡内的 .bomb-ring 做秒级刷新，归零后自动停（服务端到点会推 BOMB_EXPLODED） */
    function startBombTicker(wrap) {
        const ring = wrap.querySelector('.bomb-ring');
        if (!ring) return;
        const deadline = Date.parse((ring.getAttribute('data-deadline') || '').replace(' ', 'T'));
        if (isNaN(deadline)) return;
        const leftEl = ring.querySelector('.bomb-left');
        const tick = function () {
            const left = Math.ceil((deadline - Date.now()) / 1000);
            if (leftEl) leftEl.textContent = left > 0 ? String(left) : '0';
            if (left <= 0) {
                // 到点了，等服务端推送；这里先停止计时避免显示负数
                clearInterval(timer);
            }
        };
        const timer = setInterval(tick, 1000);
        tick();
        // 节点被移除时清理定时器，避免会话切换后残留一堆 interval
        bombTimers.push(timer);
    }

    /** 所有炸弹倒计时定时器，切换会话时统一清理 */
    const bombTimers = [];
    function clearBombTimers() {
        while (bombTimers.length) clearInterval(bombTimers.pop());
    }

    /** WS: ⑨ 炸弹引爆 → 气泡内容替换为占位文案，移除倒计时 */
    function onBombExploded(wm) {
        if (!wm || !wm.id) return;
        const node = el('chat-messages').querySelector('[data-mid="' + wm.id + '"]');
        if (!node) return;
        const bubble = node.querySelector('.bubble');
        if (!bubble) return;
        const ring = bubble.querySelector('.bomb-ring');
        if (ring) ring.remove();
        contentCache[wm.id] = wm.content || '💣 消息已引爆';
        const urgentTag = bubble.querySelector('.urgent-tag');
        bubble.innerHTML = (urgentTag ? '<div class="urgent-tag">⚡ 加急</div>' : '')
            + App.escapeHtml(wm.content || '💣 消息已引爆');
        bubble.classList.add('bomb-exploded');
    }

    /** WS: ⑨ 炸弹拆除（对方在倒计时内回复）→ 移除倒计时，消息本身保留 */
    function onBombDefused(wm) {
        if (!wm || !wm.id) return;
        const node = el('chat-messages').querySelector('[data-mid="' + wm.id + '"]');
        if (!node) return;
        const ring = node.querySelector('.bomb-ring');
        if (ring) {
            ring.classList.add('defused');
            ring.innerHTML = '✅ 已拆弹';
        }
    }

    function setInputEnabled(on) {
        const ta = el('msg-input');
        const urgent = el('btn-urgent');
        const bombBtn = el('btn-bomb');
        const voiceBtn = el('btn-voice');
        const moreBtn = el('btn-more');
        const sendBtn = el('btn-send');
        const holdBtn = el('btn-hold');
        if (ta) ta.disabled = !on;
        if (urgent) urgent.disabled = !on;
        // ⑨ 消息炸弹仅单聊文本消息可用
        const canBomb = on && App.current && App.current.type === 'USER';
        if (bombBtn) {
            bombBtn.disabled = !canBomb;
            if (!canBomb && App.bombArmed) resetBomb();
        }
        if (voiceBtn) voiceBtn.disabled = !on;
        if (moreBtn) moreBtn.disabled = !on;
        if (sendBtn) sendBtn.disabled = !on || voiceMode;
        if (holdBtn) holdBtn.disabled = !on;
        if (!on) voiceMode = false; // 离开会话时复位为文字输入
        updateUrgentBtn(); // 加急按钮状态由 updateUrgentBtn 统一计算（含冷却）
        updateBombBtn();   // ⑨ 炸弹按钮可用性随会话类型变化
        applyVoiceMode(); // 同步 文字框 / 按住说话 形态
    }

    return {
        renderConversations: renderConversations,
        refreshConversations: refreshConversations,
        syncOpenConversation: syncOpenConversation,
        openConversation: openConversation,
        renderChatHeader: renderChatHeader,
        renderChatHeaderStatus: function (assistantUserId, status) {
            // AI 状态变更时局部刷新当前打开的 AI 会话头部状态点（不重拉消息）
            App.aiStatus[assistantUserId] = status;
            const cur = App.current;
            if (cur && cur.type === 'USER' && cur.id === assistantUserId) {
                renderChatHeader('USER', assistantUserId, cur.name);
            }
        },
        closeConversation: closeConversation,
        onIncoming: onIncoming,
        onRead: onRead,
        onTyping: onTyping,
        // ⑨ 消息炸弹的实时事件
        onBombExploded: onBombExploded,
        onBombDefused: onBombDefused,
        onTypingContent: onTypingContent,
        onTypingView: onTypingView,
        openLiveView: openLiveView,
        closeLiveView: closeLiveView,
        onInput: onInput,
        onUrgent: onUrgent,
        viewUrgent: viewUrgent,
        ignoreUrgent: ignoreUrgent,
        muteUrgent: muteUrgent,
        toggleUrgentMute: toggleUrgentMute,
        toggleBomb: toggleBomb,
        onInputFocus: onInputFocus,
        onInputBlur: onInputBlur,
        sendText: sendText,
        sendImage: sendImage,
        sendVoice: sendVoice,
        voicePointerDown: voicePointerDown,
        voicePointerMove: voicePointerMove,
        voicePointerUp: voicePointerUp,
        toggleVoiceMode: toggleVoiceMode,
        saveCallRecord: saveCallRecord,
        toggleUrgent: toggleUrgent,
        setInputEnabled: setInputEnabled,
        pickImage: pickImage,
        pickFile: pickFile,
        onFilePicked: onFilePicked,
        toggleMorePanel: toggleMorePanel,
        closeMorePanel: closeMorePanel,
        onMoreClick: onMoreClick,
        onComposerInput: onComposerInput,
        onComposerKey: onComposerKey,
        toggleVoicePlay: toggleVoicePlay,
        fillVoiceDur: fillVoiceDur,
        openSearch: openSearch,
        runSearch: runSearch,
        renderUnifiedSearch: renderUnifiedSearch,
        deleteSearchResult: deleteSearchResult,
        jumpToMessage: jumpToMessage,
        onGroupDissolved: onGroupDissolved
    };
})();
