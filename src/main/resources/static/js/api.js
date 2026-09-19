/* REST 接口封装 */
window.Api = (function () {

    function request(method, url, body) {
        const opt = {
            method: method,
            headers: { 'Content-Type': 'application/json' }
        };
        if (App.token) opt.headers['X-Token'] = App.token;
        if (body !== undefined) opt.body = JSON.stringify(body);
        return fetch(url, opt).then(function (r) {
            return r.text().then(function (t) {
                try { return JSON.parse(t); } catch (e) { return { code: -1, message: t }; }
            });
        });
    }

    function q(params) {
        const parts = [];
        Object.keys(params || {}).forEach(function (k) {
            const v = params[k];
            if (v === undefined || v === null) return;
            if (Array.isArray(v)) {
                v.forEach(function (item) { parts.push(k + '=' + encodeURIComponent(item)); });
            } else {
                parts.push(k + '=' + encodeURIComponent(v));
            }
        });
        return parts.length ? ('?' + parts.join('&')) : '';
    }

    return {
        register: function (username, email, password, code, nickname) {
            return request('POST', '/api/user/register', {
                username: username, email: email, password: password, code: code, nickname: nickname
            });
        },
        login: function (account, password) {
            return request('POST', '/api/user/login', { account: account, password: password });
        },
        /** 发送邮箱验证码（注册 / 改密 / 登录 / 重置 / 绑定）：type ∈ register|changepwd|login|resetpwd|bind */
        sendCode: function (email, type) {
            return request('POST', '/api/auth/send-code', { email: email, type: type });
        },
        /** 校验邮箱验证码（注册 / 改密 / 重置场景仅标记通过，不签发 token） */
        verifyCode: function (email, code, type) {
            return request('POST', '/api/auth/verify-code', { email: email, code: code, type: type });
        },
        /** 查询验证码统一配置（冷却秒数等），前端倒计时依据 */
        codeConfig: function () {
            return request('GET', '/api/auth/code-config');
        },
        /** 修改密码（需登录 + 邮箱验证码） */
        changePassword: function (email, code, newPassword) {
            return request('POST', '/api/auth/change-password', { email: email, code: code, newPassword: newPassword });
        },
        /** 忘记密码重置（公开 + 邮箱验证码）：type=resetpwd */
        resetPassword: function (email, code, newPassword) {
            return request('POST', '/api/auth/reset-password', { email: email, code: code, newPassword: newPassword });
        },
        /** 绑定 / 换绑邮箱（需登录 + 新邮箱验证码；首次绑定需同时传 newPassword 以匹配密码格式） */
        bindEmail: function (email, code, newPassword) {
            return request('POST', '/api/auth/bind-email', { email: email, code: code, newPassword: newPassword });
        },
        logout: function () { return request('POST', '/api/user/logout'); },
        setOnline: function () { return request('POST', '/api/user/online'); },
        switchAccount: function (account, password) {
            return request('POST', '/api/user/switch', { account: account, password: password });
        },
        me: function () { return request('GET', '/api/user/me'); },
        /** 全局搜人（按账号 / 用户名 / 昵称，含陌生人）：用于「添加朋友」与消息菜单的融合搜索 */
        userSearch: function (kw) { return request('GET', '/api/user/search' + q({ kw: kw })); },
        /** 搜索可添加的 AI 助手（按账号 / 名称匹配 role=AI，含默认助手，排除自己） */
        searchAssistant: function (kw) { return request('GET', '/api/user/search/assistant' + q({ kw: kw })); },
        profile: function (id) { return request('GET', '/api/user/' + id + '/profile'); },
        updateProfile: function (fields) { return request('POST', '/api/user/profile', fields); },

        /** 上传文件到 MinIO（走后端 8080 代理），返回 {url, objectName} */
        upload: function (file, prefix) {
            const fd = new FormData();
            fd.append('file', file);
            if (prefix) fd.append('prefix', prefix);
            const opt = { method: 'POST', headers: {} };
            if (App.token) opt.headers['X-Token'] = App.token;
            opt.body = fd;
            return fetch('/api/file/upload', opt).then(function (r) {
                return r.text().then(function (t) {
                    try { return JSON.parse(t); } catch (e) { return { code: -1, message: t }; }
                });
            });
        },

        friendApply: function (to) { return request('POST', '/api/friend/apply' + q({ to: to })); },
        friendAccept: function (from) { return request('POST', '/api/friend/accept' + q({ from: from })); },
        friendReject: function (from) { return request('POST', '/api/friend/reject' + q({ from: from })); },
        friendList: function () { return request('GET', '/api/friend/list'); },
        friendRequests: function () { return request('GET', '/api/friend/requests'); },
        friendDelete: function (id) { return request('DELETE', '/api/friend' + q({ id: id })); },
        friendBlock: function (friendId) { return request('POST', '/api/friend/block' + q({ friendId: friendId })); },
        friendUnblock: function (friendId) { return request('POST', '/api/friend/unblock' + q({ friendId: friendId })); },
        /** 设置好友备注（remark 可空，传空表示清除） */
        friendRemark: function (friendId, remark) { return request('POST', '/api/friend/remark' + q({ friendId: friendId, remark: remark })); },
        /** 在「我添加的用户」中按账号 / 用户名搜索好友 */
        friendSearchMy: function (kw) { return request('GET', '/api/friend/search' + q({ kw: kw })); },

        pin: function (targetType, targetId, pinned) { return request('POST', '/api/message/pin' + q({ targetType: targetType, targetId: targetId, pinned: pinned })); },
        recall: function (messageId) { return request('POST', '/api/message/recall' + q({ messageId: messageId })); },
        read: function (targetType, targetId) { return request('POST', '/api/message/read' + q({ targetType: targetType, targetId: targetId })); },
        typing: function (targetType, targetId, typing) { return request('POST', '/api/message/typing' + q({ targetType: targetType, targetId: targetId, typing: typing })); },
        urgentMute: function (peerId, muted) { return request('POST', '/api/message/urgent-mute' + q({ peerId: peerId, muted: muted })); },
        urgentMuteList: function () { return request('GET', '/api/message/urgent-mute/list'); },

        groupCreate: function (name, memberIds) { return request('POST', '/api/group/create' + q({ name: name, memberIds: memberIds || [] })); },
        groupInvite: function (groupId, userIds) { return request('POST', '/api/group/invite' + q({ groupId: groupId, userIds: userIds || [] })); },
        groupRemove: function (groupId, userId) { return request('POST', '/api/group/remove' + q({ groupId: groupId, userId: userId })); },
        groupQuit: function (groupId) { return request('POST', '/api/group/quit' + q({ groupId: groupId })); },
        groupDissolve: function (groupId) { return request('POST', '/api/group/dissolve' + q({ groupId: groupId })); },
        groupList: function () { return request('GET', '/api/group/list'); },
        groupMembers: function (groupId) { return request('GET', '/api/group/members' + q({ groupId: groupId })); },

        history: function (targetType, targetId, page, size) {
            return request('GET', '/api/message/history' + q({ targetType: targetType, targetId: targetId, page: page || 0, size: size || 30 }));
        },
        /** 断线重连补拉：取回该会话里 id 大于 afterId 的消息（增量），用于重连后补齐漏推的消息 */
        sync: function (targetType, targetId, afterId) {
            return request('GET', '/api/message/sync' + q({ targetType: targetType, targetId: targetId, afterId: afterId || 0 }));
        },
        /** 聊天记录搜索（仿微信）：关键字必填；targetType+targetId 可限定会话范围，留空则搜全部我的会话 */
        msgSearch: function (keyword, targetType, targetId, page, size) {
            return request('GET', '/api/message/search' + q({
                keyword: keyword,
                targetType: targetType || '',
                targetId: targetId || '',
                page: page || 0,
                size: size || 30
            }));
        },
        conversations: function () { return request('GET', '/api/message/conversation/list'); },
        deleteMessage: function (id) { return request('DELETE', '/api/message/' + id); },
        deleteConversation: function (targetType, targetId) {
            return request('DELETE', '/api/message/conversation' + q({ targetType: targetType, targetId: targetId }));
        },
        restoreConversation: function (targetType, targetId) {
            return request('POST', '/api/message/conversation/restore' + q({ targetType: targetType, targetId: targetId }));
        },

        /* ===== 朋友圈 ===== */
        momentPublish: function (content, images, visibility, allowList, denyList) {
            return request('POST', '/api/moment/publish', {
                content: content,
                images: images || [],
                visibility: visibility || 'FRIENDS',
                allowList: allowList || [],
                denyList: denyList || []
            });
        },
        momentFeed: function (limit) {
            return request('GET', '/api/moment/feed' + q({ limit: limit || 50 }));
        },
        momentMine: function (limit) {
            return request('GET', '/api/moment/mine' + q({ limit: limit || 50 }));
        },
        /** 查看某好友的家园动态（后端按浏览者视角过滤可见范围）：GET /api/moment/user/{userId} */
        momentUserMoments: function (userId, limit) {
            return request('GET', '/api/moment/user/' + userId + q({ limit: limit || 50 }));
        },
        momentGetSetting: function () {
            return request('GET', '/api/moment/setting');
        },
        momentSaveSetting: function (data) {
            return request('POST', '/api/moment/setting', data || {});
        },
        momentDelete: function (id) {
            return request('DELETE', '/api/moment/' + id);
        },
        momentUpdatePermission: function (id, visibility, allowList, denyList) {
            return request('PUT', '/api/moment/' + id + '/permission', {
                visibility: visibility || 'FRIENDS',
                allowList: allowList || [],
                denyList: denyList || []
            });
        },
        momentComment: function (momentId, content, images, parentId) {
            return request('POST', '/api/moment/' + momentId + '/comment', {
                content: content || '',
                images: images || [],
                parentId: parentId || null
            });
        },
        momentComments: function (momentId) {
            return request('GET', '/api/moment/' + momentId + '/comments');
        },
        momentCommentDelete: function (commentId) {
            return request('DELETE', '/api/moment/comment/' + commentId);
        },
        /** 点赞 / 取消点赞（切换）：POST /api/moment/{id}/like，返回 { liked, likeCount } */
        momentLike: function (id) {
            return request('POST', '/api/moment/' + id + '/like');
        },
        momentGet: function (momentId) {
            return request('GET', '/api/moment/' + momentId);
        },
        /** 重新发布（被打回后修改再发）：PUT /api/moment/{id} */
        momentRepublish: function (id, content, images, visibility, allowList, denyList) {
            return request('PUT', '/api/moment/' + id, {
                content: content,
                images: images || [],
                visibility: visibility || 'FRIENDS',
                allowList: allowList || [],
                denyList: denyList || []
            });
        },
        /** 申请人工复审：AI 审核不通过后，用户主动申请管理员复核 */
        momentApplyManualReview: function (id) {
            return request('POST', '/api/moment/' + id + '/manual-review');
        },

        /* ===== 管理员 ===== */
        adminCheck: function () { return request('GET', '/api/admin/check'); },
        adminReviewList: function () { return request('GET', '/api/admin/review'); },
        adminReviewMode: function () { return request('GET', '/api/admin/review/mode'); },
        adminSetReviewMode: function (mode) { return request('POST', '/api/admin/review/mode', { mode: mode }); },
        adminApproveMoment: function (id) { return request('POST', '/api/admin/moments/' + id + '/approve'); },
        /* ===== AI 助手管理（管理员，role=AI 独立类型） ===== */
        adminListAssistants: function () { return request('GET', '/api/admin/assistants'); },
        adminCreateAssistant: function (cfg) { return request('POST', '/api/admin/assistants', cfg); },
        adminUpdateAssistant: function (id, cfg) { return request('PUT', '/api/admin/assistants/' + id, cfg); },
        adminDeleteAssistant: function (id) { return request('DELETE', '/api/admin/assistants/' + id); },

        /* ===== 通知 ===== */
        noticeList: function () { return request('GET', '/api/notice/list'); },
        noticePending: function () { return request('GET', '/api/notice/pending'); },
        noticeRead: function (noticeIds) {
            const ids = Array.isArray(noticeIds) ? noticeIds : [noticeIds];
            return request('POST', '/api/notice/read', { noticeIds: ids });
        },

        /* ===== 学习空间：AI 工具（复用统一 X-Token 鉴权） ===== */
        /** AI 定制学习计划：{ goal, duration, level } */
        aiPlan: function (goal, duration, level) {
            return request('POST', '/api/ai/plan', { goal: goal, duration: duration, level: level || '' });
        },
        /** 知识点互问：{ subject, weakPoints, count } */
        aiQuiz: function (subject, weakPoints, count) {
            return request('POST', '/api/ai/quiz', { subject: subject, weakPoints: weakPoints, count: count || 5 });
        },
        /** 资料/错题摘要归类：{ content } */
        aiSummarize: function (content) {
            return request('POST', '/api/ai/summarize', { content: content });
        },
        /** 通用 AI 对话：{ system, prompt } */
        aiChat: function (system, prompt) {
            return request('POST', '/api/ai/chat', { system: system || '', prompt: prompt });
        },
        /** moyo 助手帮写朋友圈/广场文案：{ topic } */
        aiAssistantCopy: function (topic) {
            return request('POST', '/api/ai/assistant/copy', { topic: topic || '' });
        },
        /**
         * AI 流式对话（SSE）。req 为 { mode, prompt, goal, duration, level, subject, weakPoints, count }。
         * 返回 Promise；onToken(delta) 每收到一个增量片段回调，onDone() 结束回调，onError(msg) 出错回调。
         * 前端用 fetch + ReadableStream 解析 text/event-stream，实现打字机效果。
         */
        aiStream: function (req, onToken, onDone, onError) {
            var controller = new AbortController();
            var p = new Promise(function (resolve, reject) {
                var body = JSON.stringify(req || {});
                fetch('/api/ai/stream', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                        'Accept': 'text/event-stream',
                        'X-Token': App.token || ''
                    },
                    body: body,
                    signal: controller.signal
                }).then(function (resp) {
                    if (!resp.ok) {
                        // 非 200：尝试读取错误体
                        resp.text().then(function (t) {
                            var m = '请求失败(' + resp.status + ')';
                            try { var d = JSON.parse(t); if (d && d.message) m = d.message; } catch (e) {}
                            if (onError) onError(m);
                        });
                        reject(new Error('HTTP ' + resp.status));
                        return;
                    }
                    var reader = resp.body.getReader();
                    var decoder = new TextDecoder('utf-8');
                    var buffer = '';
                    function pump() {
                        return reader.read().then(function (r) {
                            if (r.done) {
                                flush();
                                if (onDone) onDone();
                                resolve();
                                return;
                            }
                            buffer += decoder.decode(r.value, { stream: true });
                            var idx;
                            while ((idx = buffer.indexOf('\n\n')) !== -1) {
                                var raw = buffer.slice(0, idx);
                                buffer = buffer.slice(idx + 2);
                                handleEvent(raw);
                            }
                            return pump();
                        }).catch(function (err) {
                            if (onError) onError('读取流失败：' + err.message);
                            reject(err);
                        });
                    }
                    function flush() {
                        if (buffer.trim()) { handleEvent(buffer); buffer = ''; }
                    }
                    function handleEvent(raw) {
                        // SSE 事件：多行 data:/name: 拼接
                        var lines = raw.split('\n');
                        var name = 'message';
                        var dataLines = [];
                        for (var i = 0; i < lines.length; i++) {
                            var ln = lines[i];
                            if (ln.indexOf('name:') === 0) { name = ln.slice(5).trim(); }
                            else if (ln.indexOf('data:') === 0) { dataLines.push(ln.slice(5).trim()); }
                        }
                        if (dataLines.length === 0) return;
                        var data = dataLines.join('\n');
                        if (name === 'done') { if (onDone) onDone(); resolve(); return; }
                        if (name === 'error') { if (onError) onError(data); reject(new Error(data)); return; }
                        if (data === '[DONE]') return;
                        if (onToken) onToken(data);
                    }
                    return pump();
                }).catch(function (err) {
                    if (err && err.name === 'AbortError') {
                        // 用户主动停止：不算错误，正常收尾
                        if (onDone) onDone(true);
                        resolve();
                        return;
                    }
                    if (onError) onError(err.message || '网络异常');
                    reject(err);
                });
            });
            // 暴露 abort：调用方可在流式过程中主动停止生成
            p.abort = function () { try { controller.abort(); } catch (e) {} };
            return p;
        },

        /* ===== 学习空间：我的计划（持久化） ===== */
        /** 保存计划：{ title, content } */
        studySavePlan: function (title, content) {
            return request('POST', '/api/study/plan', { title: title, content: content || '' });
        },
        /** 我的计划列表 */
        studyMyPlans: function () {
            return request('GET', '/api/study/plans');
        },
        /** 查看单条计划 */
        studyGetPlan: function (id) {
            return request('GET', '/api/study/plan/' + id);
        },
        /** 整体更新计划正文（勾选/编辑/增删步骤后整份落库）：{ content } */
        studyUpdatePlan: function (id, content) {
            return request('PUT', '/api/study/plan/' + id, { content: content || '' });
        },
        /** 删除计划 */
        studyDeletePlan: function (id) {
            return request('DELETE', '/api/study/plan/' + id);
        },
        /** 计划打卡：{ minutes } */
        studyCheckin: function (id, minutes) {
            return request('POST', '/api/study/plan/' + id + '/checkin', { minutes: minutes || 0 });
        },
        /** 番茄钟完成上报：{ minutes } */
        studyPomodoroReport: function (minutes) {
            return request('POST', '/api/study/pomodoro/report', { minutes: minutes || 0 });
        },
        /** 学习统计总览：今日 + 累计 */
        studyStats: function () {
            return request('GET', '/api/study/stats');
        },

        /* ===== 学习空间：AI 对话（独立会话框，后端持久化） ===== */
        /** 我的 AI 会话列表（含消息数） */
        studySessions: function () {
            return request('GET', '/api/study/chat/sessions');
        },
        /** 新建 AI 会话：{ title } */
        studyCreateSession: function (title) {
            return request('POST', '/api/study/chat/session', { title: title || '新会话' });
        },
        /** 删除 AI 会话（连同旗下消息） */
        studyDeleteSession: function (id) {
            return request('DELETE', '/api/study/chat/session/' + id);
        },
        /** 拉取某会话的 AI 对话历史（按 seq 升序） */
        studyChatList: function (sessionId) {
            return request('GET', '/api/study/chat/list' + (sessionId ? '?sessionId=' + sessionId : ''));
        },
        /** 发送一轮：在指定会话内追加 user + 创建 ai 占位，返回 { id, ...ai记录 } */
        studyChatSend: function (sessionId, mode, prompt) {
            return request('POST', '/api/study/chat/send', { sessionId: sessionId || 0, mode: mode, prompt: prompt });
        },
        /** 流式过程中实时更新 AI 回复内容：{ id, content } */
        studyChatUpdate: function (id, content) {
            return request('POST', '/api/study/chat/update', { id: id, content: content });
        },
        /** 删除单条对话 */
        studyChatDelete: function (id) {
            return request('DELETE', '/api/study/chat/' + id);
        },
        /** 清空对话：传 sessionId + thread 清空某条子线；不传 thread 则清空整个会话 */
        studyChatClear: function (sessionId, thread) {
            return request('DELETE', '/api/study/chat/clear' + q({ sessionId: sessionId, thread: thread }));
        },

        /* ===== 程序空间：绘画作品管理（CRUD） ===== */
        /** 我的作品列表 */
        paintingList: function () {
            return request('GET', '/api/painting/list');
        },
        /** 创建作品：{ title, description, imageUrl } */
        paintingCreate: function (title, description, imageUrl) {
            return request('POST', '/api/painting/create', {
                title: title, description: description || '', imageUrl: imageUrl
            });
        },
        /** 删除作品 */
        paintingDelete: function (id) {
            return request('DELETE', '/api/painting/' + id);
        },

        /* ===== ① 时间胶囊 ===== */
        /** 我相关的胶囊（我写的 + 别人写给我的），未解锁的不含正文 */
        capsuleList: function () {
            return request('GET', '/api/capsule/list');
        },
        /** 创建胶囊：{ receiverId(可空=写给自己), content, openDate:'yyyy-MM-dd' } */
        capsuleCreate: function (receiverId, content, openDate) {
            return request('POST', '/api/capsule/create', {
                receiverId: receiverId || null, content: content, openDate: openDate
            });
        },
        /** 胶囊详情 */
        capsuleView: function (id) {
            return request('GET', '/api/capsule/' + id);
        },

        /* ===== ③ 用户开关设置 ===== */
        settingGet: function () {
            return request('GET', '/api/setting');
        },
        /** 设置隐身阅读 */
        setGhostRead: function (enabled) {
            return request('POST', '/api/setting/ghost-read', { enabled: !!enabled });
        },

        /* ===== ⑪ 聊天挖矿：积分 / 亲密度 ===== */
        pointsMe: function () {
            return request('GET', '/api/points/me');
        },
        pointsLog: function (limit) {
            return request('GET', '/api/points/log' + (limit ? '?limit=' + limit : ''));
        },
        /** 与某好友的亲密度 */
        intimacy: function (peerId) {
            return request('GET', '/api/points/intimacy?peerId=' + peerId);
        },

        /* ===== MCP 功能（管理端可配置 / 启停） ===== */
        /** 列出当前已启用的 MCP 功能（前端据此决定悬浮窗展示哪些能力） */
        mcpFunctions: function () {
            return request('GET', '/api/mcp/functions');
        },
        /** message_sender 预览：{ instruction?, recipients?:[{type,value}], content? } */
        mcpPreview: function (body) {
            return request('POST', '/api/mcp/message-sender/preview', body);
        },
        /** message_sender 发送：{ recipients:[{type,value}], content } */
        mcpSend: function (body) {
            return request('POST', '/api/mcp/message-sender/send', body);
        }
    };
})();
