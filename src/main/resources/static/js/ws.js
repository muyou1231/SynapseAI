/* WebSocket（STOMP over SockJS）连接与收发。
 * 关键能力：断线后「指数退避自动重连」+ 每次重连重新订阅全部主题 + 前后台切换/网络恢复立即重连，
 * 解决手机端熄屏/切后台/网络切换后连接被系统丢弃、收不到新消息与通话信令、必须手动刷新才能恢复的问题。
 */
window.Ws = {
    client: null,
    token: null,
    connected: false,
    reconnectAttempts: 0,
    reconnectTimer: null,
    manualClose: false,        // 主动断开（退出登录/切换账号）后不再自动重连
    maxBackoff: 15000,         // 重连退避上限 15s
    banner: null,

    connect: function (token) {
        if (typeof SockJS === 'undefined' || typeof Stomp === 'undefined') {
            console.error('SockJS / Stomp 未加载');
            return;
        }
        this.token = token;
        this.manualClose = false;
        this.reconnectAttempts = 0;
        this._doConnect();
    },

    _doConnect: function () {
        if (!this.token) return;          // 尚未登录，不连接
        if (this.client && this.client.connected) return; // 已连，避免重复
        const socket = new SockJS('/ws');
        const client = Stomp.over(socket);
        if (client.debug) client.debug = null;
        // 连接保活：客户端每 15s 主动发一次 STOMP 心跳帧（\n），让移动端 NAT/运营商在「空闲」时不回收连接。
        // incoming 设为 0：不要求服务端回心跳（Spring 6.0 的 simple broker 不发 STOMP 心跳，强求会自断）。
        client.heartbeat = { outgoing: 15000, incoming: 0 };
        this.client = client;

        const self = this;
        client.connect({ token: this.token },
            function () {                 // onConnected
                self.connected = true;
                self.reconnectAttempts = 0;
                self._hideBanner();
                self._subscribeAll();
                self._onReconnect();      // 重连成功：补拉缺失消息 + 刷新会话
            },
            function () {                 // onError（连接失败 / 中途断开）
                self.connected = false;
                self._scheduleReconnect();
            }
        );
        // 兜底：SockJS 底层 socket 关闭事件（部分移动端只会走这里，不触发 Stomp error）
        try {
            socket.onclose = function () {
                if (self.client !== client) return; // 已被新连接取代
                self.connected = false;
                self._scheduleReconnect();
            };
        } catch (e) {}
    },

    /** 重连成功后重新订阅「个人通道 + 群通道」。每次连接都是全新订阅集合，不会重复。 */
    _subscribeAll: function () {
        const self = this;
        // 个人消息 / 好友申请 / 通话信令 / 通知 等，全部走 /topic/user/{id} 单播
        this.client.subscribe('/topic/user/' + App.user.id, function (frame) {
            try {
                const wm = JSON.parse(frame.body);
                if (wm && wm.type === 'FRIEND_REQUEST') {
                    if (window.Friend) Friend.onRequest(wm);
                } else if (wm && wm.type === 'FRIEND_ACCEPTED') {
                    const u = wm.user || {};
                    const name = u.nickname || u.username || '对方';
                    App.notify(name + ' 已通过你的好友申请');
                    if (window.Friend && App.activeTab === 'contacts') Friend.renderContacts();
                    Chat.refreshConversations();
                } else if (wm && wm.type === 'READ') {
                    Chat.onRead(wm);
                } else if (wm && wm.type === 'TYPING') {
                    Chat.onTyping(wm);
                } else if (wm && wm.type === 'TYPING_CONTENT') {
                    Chat.onTypingContent(wm);
                } else if (wm && wm.type === 'TYPING_VIEW') {
                    Chat.onTypingView(wm);
                } else if (wm && wm.type === 'URGENT_LIMIT') {
                    App.notify(wm.reason || '加急消息过于频繁，请稍后再试');
                } else if (wm && wm.type === 'GROUP_JOINED') {
                    if (window.Group) Group.onGroupEvent(wm);
                } else if (wm && wm.type === 'GROUP_DISSOLVED') {
                    if (window.Group) Group.onGroupDissolved(wm);
                } else if (wm && wm.type === 'PRESENCE') {
                    // 好友在线状态变更
                    App.online[wm.userId] = !!wm.online;
                    if (App.activeTab === 'contacts' && window.Friend) Friend.renderContacts();
                    if (App.activeTab === 'chat' && window.Chat) Chat.renderConversations();
                } else if (wm && wm.type === 'ASSISTANT_STATUS') {
                    // AI 助手状态变更（在线/离线/忙碌/维修）：实时刷新状态点，无需手动刷新
                    const aid = wm.assistantUserId;
                    if (aid != null) App.aiStatus[aid] = wm.status;
                    if (App.activeTab === 'contacts' && window.Friend) Friend.renderContacts();
                    if (App.activeTab === 'chat' && window.Chat) {
                        Chat.renderConversations();
                        // 若当前正打开该 AI 会话，刷新聊天头部状态（如头部显示状态点）
                        const cur = App.current;
                        if (cur && cur.type === 'USER' && cur.id === aid) {
                            if (window.Chat) Chat.renderChatHeaderStatus(aid, wm.status);
                        }
                    }
                } else if (wm && wm.type === 'KICK') {
                    // 被管理员强制下线：断开连接并回到登录页
                    App.notify(wm.reason || '您已被强制下线');
                    Ws.disconnect();
                    App.showLoginView();
                } else if (wm && wm.type === 'NOTICE') {
                    // 管理员下发的通知：实时弹窗提醒（已读后不再弹）
                    App.showNoticePopup([{
                        id: wm.id,
                        title: wm.title,
                        content: wm.content,
                        refId: wm.refId, // 关联业务 id（被打回的动态 id），点击弹窗可跳转到对应内容
                        createTime: wm.createTime,
                        readWaitSeconds: wm.readWaitSeconds
                    }], true);
                    // 同步变化：若用户正在看「多多的家园」，自动刷新（如动态被打回/通过，状态实时更新）
                    if (App.activeTab === 'moments' && window.Moment) Moment.render();
                } else if (wm && wm.type === 'CALL_OFFER') {
                    // 来电：邀请你语音通话
                    if (window.Call) Call.onSignal(wm);
                } else if (wm && wm.type === 'CALL_ANSWER') {
                    // 对方接听，返回 answer
                    if (window.Call) Call.onSignal(wm);
                } else if (wm && wm.type === 'CALL_ICE') {
                    // ICE candidate
                    if (window.Call) Call.onSignal(wm);
                } else if (wm && wm.type === 'CALL_BYE') {
                    // 对方挂断 / 取消 / 拒绝 / 忙线
                    if (window.Call) Call.onSignal(wm);
                } else if (wm && wm.type === 'NOTICE_NEW') {
                    // 新通知到达（如朋友圈评论/回复）：刷新铃铛红点，若通知中心(非系统弹窗)正打开则实时重渲染列表
                    if (App.refreshNoticeBadge) App.refreshNoticeBadge();
                    var nm = document.getElementById('notice-modal');
                    if (nm && !nm.classList.contains('hidden') && App._noticeIsPopup === false && App.openNoticeCenter) {
                        App.openNoticeCenter(); // 保留当前标签页(系统/用户)实时刷新
                    }
                    // 同步变化：正在看朋友圈时，新评论/回复实时刷新列表（状态与红点保持最新）
                    if (App.activeTab === 'moments' && window.Moment) Moment.render();
                } else if (wm && wm.type === 'MOMENT_AI_REVIEW_DONE') {
                    // 自己发布的动态 AI 审核完成：实时刷新「我的/广场」状态徽标（无需手动刷新页面）
                    if (window.Moment) Moment.onAiReviewDone(wm);
                } else if (wm && wm.type === 'MOMENT_MANUAL_REVIEW') {
                    // 人工复审链路状态变化（申请/打回/通过）：作者端实时刷新动态卡片状态
                    if (window.Moment) Moment.onManualReviewUpdate(wm);
                } else if (wm && wm.type === 'BOMB_EXPLODED') {
                    // ⑨ 消息炸弹引爆：气泡内容替换为占位文案并停掉倒计时
                    if (window.Chat) Chat.onBombExploded(wm);
                    App.notify('💣 一条消息炸弹引爆了');
                } else if (wm && wm.type === 'BOMB_DEFUSED') {
                    // ⑨ 对方在倒计时内回复，炸弹拆除：停掉倒计时
                    if (window.Chat) Chat.onBombDefused(wm);
                } else if (wm && wm.type === 'CAPSULE_UNLOCKED') {
                    // ① 时间胶囊到点解锁：提示用户去程序空间拆开
                    App.notify('⏳ ' + (wm.title || '时间胶囊已解锁'));
                    if (App.activeTab === 'program' && window.Program && Program.loadCapsules) Program.loadCapsules();
                } else {
                    Chat.onIncoming(wm);
                }
            } catch (e) { console.error(e); }
        });
        // 群消息
        if (window.Group) Group.subscribeMyGroups();
    },

    /** 重连成功后的补偿动作：刷新会话列表 + 补拉当前会话漏掉的消息 + 同步在线状态 */
    _onReconnect: function () {
        if (window.Chat) {
            Chat.refreshConversations();
            Chat.syncOpenConversation();
        }
        if (window.Api && Api.setOnline) Api.setOnline();
        // 若通话仍在进行（信令通道曾断开又恢复），给个提示，让用户知道可以继续
        if (window.Call && Call._state && Call._state.role) {
            App.notify('网络已恢复');
        }
    },

    /** 安排一次退避重连（指数退避 + 随机抖动，避免多端同时重连打爆服务端） */
    _scheduleReconnect: function () {
        if (this.manualClose || !this.token) return;
        if (this.reconnectTimer) return; // 已在排队
        this._showBanner();
        const self = this;
        let delay = Math.min(this.maxBackoff, 1000 * Math.pow(2, this.reconnectAttempts));
        delay += Math.floor(Math.random() * 1000); // 0~1s 抖动
        this.reconnectAttempts++;
        this.reconnectTimer = setTimeout(function () {
            self.reconnectTimer = null;
            if (self.manualClose || !self.token) return;
            if (self.client && self.client.connected) return;
            self._doConnect();
        }, delay);
    },

    /** 由外部（页面重新可见 / 网络恢复）触发「立即重连」，不走退避等待 */
    reconnectNow: function () {
        if (this.manualClose || !this.token) return;
        if (this.client && this.client.connected) return;
        if (this.reconnectTimer) { clearTimeout(this.reconnectTimer); this.reconnectTimer = null; }
        this.reconnectAttempts = 0;
        this._doConnect();
    },

    /** 顶部连接状态横幅：断线时显示「连接已断开，正在重连…」 */
    _showBanner: function () {
        if (!this.banner) {
            const b = document.createElement('div');
            b.id = 'ws-banner';
            b.textContent = '连接已断开，正在重连…';
            b.style.cssText = 'position:fixed;top:0;left:0;right:0;z-index:9999;' +
                'background:#fa9c3b;color:#fff;font-size:13px;text-align:center;' +
                'padding:6px 10px;box-shadow:0 1px 4px rgba(0,0,0,.2);';
            document.body.appendChild(b);
            this.banner = b;
        }
        this.banner.style.display = 'block';
    },
    _hideBanner: function () {
        if (this.banner) this.banner.style.display = 'none';
    },

    send: function (dto) {
        if (!this.client || !this.client.connected) {
            // 未连接：尝试立即重连，并提示用户（发送失败的实际拦截由 chat.js 的 doSend 处理）
            this.reconnectNow();
            console.warn('WebSocket 未连接，已尝试重连');
            return false;
        }
        this.client.send('/app/chat.send', {}, JSON.stringify(dto));
        return true;
    },

    disconnect: function () {
        this.manualClose = true;
        if (this.reconnectTimer) { clearTimeout(this.reconnectTimer); this.reconnectTimer = null; }
        if (this.client) {
            try { this.client.disconnect(); } catch (e) {}
            this.client = null;
        }
        this.connected = false;
        this._hideBanner();
    }
};

/* ---------- 移动端关键：页面重新可见 / 网络恢复 时立即重连 ----------
 * 手机浏览器在熄屏、切到别的 App、锁屏后会将 JS 与 WebSocket 挂起甚至杀掉；
 * 等用户回到聊天时连接早已死亡。这里在「重新可见」「网络恢复」时主动重连，
 * 无需用户手动刷新即可恢复消息与通话信令的实时接收。 */
document.addEventListener('visibilitychange', function () {
    if (!document.hidden) Ws.reconnectNow();
});
window.addEventListener('online', function () { Ws.reconnectNow(); });
window.addEventListener('offline', function () { Ws._showBanner(); });
