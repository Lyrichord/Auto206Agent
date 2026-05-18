// Auto206Agent 前端应用
class Auto206AgentApp {
    /** 与上传时的 sessionId 一致，避免刷新页面后新 UUID 导致「本会话上传」在 Milvus 中检索不到 */
    static ACTIVE_SESSION_STORAGE_KEY = 'superbiz-active-session-id';

    constructor() {
        this.apiBaseUrl = 'http://localhost:9900/api';
        this.currentMode = 'quick'; // 'quick' 或 'stream'
        const restored = Auto206AgentApp.readPersistedSessionId();
        this.sessionId = restored || this.generateSessionId();
        Auto206AgentApp.persistSessionId(this.sessionId);
        this.isStreaming = false;
        this.currentChatHistory = []; // 当前对话的消息历史
        this.chatHistories = this.loadChatHistories(); // 所有历史对话
        this.isCurrentChatFromHistory = false; // 标记当前对话是否是从历史记录加载的
        
        this.initializeElements();
        this.bindEvents();
        this.updateUI();
        this.initMarkdown();
        this.checkAndSetCentered();
        this.renderChatHistory();
        // 与服务器持久化会话合并（localStorage 清空后仍可从磁盘恢复列表）
        this.bootstrapHistoriesFromServer();
        // 当前会话可能尚未写入 chatHistories（仅存在 sessionId），先占位避免首条消息前侧栏为空
        this.ensureSidebarPlaceholderForCurrentSession();
        /** @type {{ mimeType: string, data: string }[]} 待发对话附图（Base64，无 data: 前缀） */
        this.pendingChatImages = [];
    }

    static readPersistedSessionId() {
        try {
            const v = localStorage.getItem(Auto206AgentApp.ACTIVE_SESSION_STORAGE_KEY);
            if (!v || v.length < 8 || v.length > 128) {
                return null;
            }
            if (!/^[a-zA-Z0-9._-]+$/.test(v)) {
                return null;
            }
            return v;
        } catch (e) {
            return null;
        }
    }

    static persistSessionId(sessionId) {
        if (!sessionId) {
            return;
        }
        try {
            localStorage.setItem(Auto206AgentApp.ACTIVE_SESSION_STORAGE_KEY, sessionId);
        } catch (e) {
            /* 隐私模式等 */
        }
    }

    // 初始化Markdown配置
    initMarkdown() {
        // 等待 marked 库加载完成
        const checkMarked = () => {
            if (typeof marked !== 'undefined') {
                try {
                    // 配置marked选项
                    marked.setOptions({
                        breaks: true,  // 支持GFM换行
                        gfm: true,     // 启用GitHub风格的Markdown
                        headerIds: false,
                        mangle: false
                    });

                    // 配置代码高亮
                    if (typeof hljs !== 'undefined') {
                        marked.setOptions({
                            highlight: function(code, lang) {
                                if (lang && hljs.getLanguage(lang)) {
                                    try {
                                        return hljs.highlight(code, { language: lang }).value;
                                    } catch (err) {
                                        console.error('代码高亮失败:', err);
                                    }
                                }
                                return code;
                            }
                        });
                    }
                    console.log('Markdown 渲染库初始化成功');
                } catch (e) {
                    console.error('Markdown 配置失败:', e);
                }
            } else {
                // 如果 marked 还没加载，等待一段时间后重试
                setTimeout(checkMarked, 100);
            }
        };
        checkMarked();
    }

    // 安全地渲染 Markdown
    renderMarkdown(content) {
        if (!content) return '';
        
        // 检查 marked 是否可用
        if (typeof marked === 'undefined') {
            console.warn('marked 库未加载，使用纯文本显示');
            return this.escapeHtml(content);
        }
        
        try {
            const html = marked.parse(content);
            return html;
        } catch (e) {
            console.error('Markdown 渲染失败:', e);
            return this.escapeHtml(content);
        }
    }

    // 高亮代码块
    highlightCodeBlocks(container) {
        if (typeof hljs !== 'undefined' && container) {
            try {
                container.querySelectorAll('pre code').forEach((block) => {
                    if (!block.classList.contains('hljs')) {
                        hljs.highlightElement(block);
                    }
                });
            } catch (e) {
                console.error('代码高亮失败:', e);
            }
        }
    }

    // 初始化DOM元素
    initializeElements() {
        // 侧边栏元素
        this.sidebar = document.querySelector('.sidebar');
        this.newChatBtn = document.getElementById('newChatBtn');
        this.serverMonitorBtn = document.getElementById('serverMonitorBtn');
        this.serverMonitorSidebarBtn = document.getElementById('serverMonitorSidebarBtn');
        this.serverMonitorModal = document.getElementById('serverMonitorModal');
        this.serverMonitorBackdrop = document.getElementById('serverMonitorBackdrop');
        this.serverMonitorBody = document.getElementById('serverMonitorBody');
        this.serverMonitorCloseBtn = document.getElementById('serverMonitorCloseBtn');
        this.serverMonitorRefreshBtn = document.getElementById('serverMonitorRefreshBtn');
        this.serverMonitorRefreshHint = document.getElementById('serverMonitorRefreshHint');
        this.serverMonitorPollTimer = null;
        this.serverMonitorRefreshSec = 10;
        this.aiOpsSidebarBtn = document.getElementById('aiOpsSidebarBtn');
        this.researchFeedSidebarBtn = document.getElementById('researchFeedSidebarBtn');
        this.researchFeedModal = document.getElementById('researchFeedModal');
        this.researchFeedBackdrop = document.getElementById('researchFeedBackdrop');
        this.researchFeedBody = document.getElementById('researchFeedBody');
        this.researchFeedCloseBtn = document.getElementById('researchFeedCloseBtn');
        this.researchFeedFetchBtn = document.getElementById('researchFeedFetchBtn');
        this.researchFeedMoreBtn = document.getElementById('researchFeedMoreBtn');
        this.researchFeedHint = document.getElementById('researchFeedHint');
        this.researchFeedList = document.getElementById('researchFeedList');
        this.researchFeedSessionId = null;
        this.deleteSessionConfirmModal = document.getElementById('deleteSessionConfirmModal');
        this.deleteSessionConfirmBackdrop = document.getElementById('deleteSessionConfirmBackdrop');
        this.deleteSessionConfirmCancel = document.getElementById('deleteSessionConfirmCancel');
        this.deleteSessionConfirmOk = document.getElementById('deleteSessionConfirmOk');
        this.deleteSessionConfirmName = document.getElementById('deleteSessionConfirmName');
        this.pendingDeleteHistoryId = null;
        this._deleteConfirmOnKeydown = null;
        
        // 输入区域元素
        this.messageInput = document.getElementById('messageInput');
        this.sendButton = document.getElementById('sendButton');
        this.uploadFileBtn = document.getElementById('uploadFileBtn');
        this.modeSelectorBtn = document.getElementById('modeSelectorBtn');
        this.modeDropdown = document.getElementById('modeDropdown');
        this.currentModeText = document.getElementById('currentModeText');
        this.fileInput = document.getElementById('fileInput');
        this.chatImageInput = document.getElementById('chatImageInput');
        this.chatImageBtn = document.getElementById('chatImageBtn');
        this.chatImagePreviewBar = document.getElementById('chatImagePreviewBar');
        
        // 聊天区域元素
        this.chatMessages = document.getElementById('chatMessages');
        this.loadingOverlay = document.getElementById('loadingOverlay');
        this.chatContainer = document.querySelector('.chat-container');
        this.welcomeGreeting = document.getElementById('welcomeGreeting');
        this.chatHistoryList = document.getElementById('chatHistoryList');
        
        // 初始化时检查是否需要居中
        this.checkAndSetCentered();
    }

    // 绑定事件监听器
    bindEvents() {
        // 新建对话
        if (this.newChatBtn) {
            this.newChatBtn.addEventListener('click', () => this.newChat());
        }
        
        // AI Ops按钮
        if (this.serverMonitorBtn) {
            this.serverMonitorBtn.addEventListener('click', () => void this.openServerMonitorModal());
        }
        if (this.serverMonitorSidebarBtn) {
            this.serverMonitorSidebarBtn.addEventListener('click', () => void this.openServerMonitorModal());
        }
        if (this.serverMonitorCloseBtn) {
            this.serverMonitorCloseBtn.addEventListener('click', () => this.closeServerMonitorModal());
        }
        if (this.serverMonitorBackdrop) {
            this.serverMonitorBackdrop.addEventListener('click', () => this.closeServerMonitorModal());
        }
        if (this.serverMonitorRefreshBtn) {
            this.serverMonitorRefreshBtn.addEventListener('click', () => this.fetchServerMonitorSnapshot());
        }
        if (this.aiOpsSidebarBtn) {
            this.aiOpsSidebarBtn.addEventListener('click', () => this.triggerAIOps());
        }
        if (this.researchFeedSidebarBtn) {
            this.researchFeedSidebarBtn.addEventListener('click', () => this.openResearchFeedModal());
        }
        if (this.researchFeedCloseBtn) {
            this.researchFeedCloseBtn.addEventListener('click', () => this.closeResearchFeedModal());
        }
        if (this.researchFeedBackdrop) {
            this.researchFeedBackdrop.addEventListener('click', () => this.closeResearchFeedModal());
        }
        if (this.researchFeedFetchBtn) {
            this.researchFeedFetchBtn.addEventListener('click', () => void this.fetchResearchFeedStart());
        }
        if (this.researchFeedMoreBtn) {
            this.researchFeedMoreBtn.addEventListener('click', () => void this.fetchResearchFeedMore());
        }
        if (this.deleteSessionConfirmBackdrop) {
            this.deleteSessionConfirmBackdrop.addEventListener('click', () => this.closeDeleteSessionConfirmModal());
        }
        if (this.deleteSessionConfirmCancel) {
            this.deleteSessionConfirmCancel.addEventListener('click', () => this.closeDeleteSessionConfirmModal());
        }
        if (this.deleteSessionConfirmOk) {
            this.deleteSessionConfirmOk.addEventListener('click', () => void this.confirmDeleteSession());
        }
        
        // 模式选择下拉菜单
        if (this.modeSelectorBtn) {
            this.modeSelectorBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                this.toggleModeDropdown();
            });
        }
        
        // 下拉菜单项点击
        const dropdownItems = document.querySelectorAll('.dropdown-item');
        dropdownItems.forEach(item => {
            item.addEventListener('click', (e) => {
                const mode = item.getAttribute('data-mode');
                this.selectMode(mode);
                this.closeModeDropdown();
            });
        });
        
        // 点击外部关闭下拉菜单
        document.addEventListener('click', (e) => {
            if (!this.modeSelectorBtn.contains(e.target) && 
                !this.modeDropdown.contains(e.target)) {
                this.closeModeDropdown();
            }
        });
        
        // 发送消息
        if (this.sendButton) {
            this.sendButton.addEventListener('click', () => this.sendMessage());
        }
        
        if (this.messageInput) {
            this.messageInput.addEventListener('keypress', (e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault();
                    this.sendMessage();
                }
            });
        }
        
        if (this.uploadFileBtn && this.fileInput) {
            this.uploadFileBtn.addEventListener('click', () => this.fileInput.click());
        }
        
        if (this.fileInput) {
            this.fileInput.addEventListener('change', (e) => this.handleFileSelect(e));
        }

        if (this.chatImageBtn && this.chatImageInput) {
            this.chatImageBtn.addEventListener('click', () => this.chatImageInput.click());
            this.chatImageInput.addEventListener('change', (e) => void this.onChatImageFilesSelected(e));
        }
    }

    renderChatImagePreviewBar() {
        const bar = this.chatImagePreviewBar;
        if (!bar) {
            return;
        }
        const list = this.pendingChatImages || [];
        if (list.length === 0) {
            bar.innerHTML = '';
            bar.hidden = true;
            return;
        }
        bar.hidden = false;
        bar.innerHTML = '';
        list.forEach((img, idx) => {
            const pill = document.createElement('span');
            pill.className = 'chat-image-pill';
            const thumb = document.createElement('img');
            thumb.alt = '附图';
            thumb.src = `data:${img.mimeType};base64,${img.data}`;
            const lbl = document.createElement('span');
            lbl.textContent = `图${idx + 1}`;
            const rm = document.createElement('button');
            rm.type = 'button';
            rm.className = 'chat-image-pill-remove';
            rm.setAttribute('aria-label', '移除');
            rm.textContent = '×';
            rm.addEventListener('click', () => {
                this.pendingChatImages = (this.pendingChatImages || []).filter((_, i) => i !== idx);
                this.renderChatImagePreviewBar();
            });
            pill.appendChild(thumb);
            pill.appendChild(lbl);
            pill.appendChild(rm);
            bar.appendChild(pill);
        });
    }

    async onChatImageFilesSelected(ev) {
        const input = ev.target;
        const files = Array.from(input.files || []);
        input.value = '';
        if (!files.length) {
            return;
        }
        const maxBytes = 4 * 1024 * 1024;
        for (const file of files) {
            if (this.pendingChatImages.length >= 3) {
                this.showNotification('最多添加 3 张图片', 'warning');
                break;
            }
            if (!file.type || !file.type.startsWith('image/')) {
                continue;
            }
            if (file.size > maxBytes) {
                this.showNotification(`${file.name || '图片'} 超过 4MB，已跳过`, 'warning');
                continue;
            }
            const dataUrl = await new Promise((resolve, reject) => {
                const r = new FileReader();
                r.onload = () => resolve(r.result);
                r.onerror = () => reject(r.error);
                r.readAsDataURL(file);
            });
            const m = typeof dataUrl === 'string' && /^data:([^;]+);base64,(.+)$/i.exec(dataUrl);
            if (m) {
                this.pendingChatImages.push({ mimeType: m[1], data: m[2] });
            }
        }
        this.renderChatImagePreviewBar();
    }

    // 新建对话
    newChat() {
        if (this.isStreaming) {
            this.showNotification('请等待当前对话完成后再新建对话', 'warning');
            return;
        }
        
        // 如果当前有对话内容，且不是从历史记录加载的，才保存为新的历史对话
        // 如果是从历史记录加载的，只需要更新该历史记录
        if (this.currentChatHistory.length > 0) {
            if (this.isCurrentChatFromHistory) {
                // 当前对话是从历史记录加载的，更新该历史记录
                this.updateCurrentChatHistory();
            } else {
                // 当前对话是新对话，保存为新的历史对话
                this.saveCurrentChat();
            }
        }
        
        // 停止所有进行中的操作
        this.isStreaming = false;
        
        // 清空输入框
        if (this.messageInput) {
            this.messageInput.value = '';
        }
        this.pendingChatImages = [];
        this.renderChatImagePreviewBar();
        
        // 清空当前对话历史
        this.currentChatHistory = [];
        
        // 重置标记
        this.isCurrentChatFromHistory = false;
        
        // 清空聊天记录
        if (this.chatMessages) {
            this.chatMessages.innerHTML = '';
        }
        
        // 生成新的会话ID
        this.sessionId = this.generateSessionId();
        Auto206AgentApp.persistSessionId(this.sessionId);

        // 重置模式为快速
        this.currentMode = 'quick';
        this.updateUI();
        
        // 重新设置居中样式（确保对话框居中显示）
        this.checkAndSetCentered();
        
        // 确保容器有过渡动画
        if (this.chatContainer) {
            this.chatContainer.style.transition = 'all 0.5s ease';
        }
        
        // 立即在侧栏插入「新对话」占位，避免必须刷新或切换后才出现
        this.ensureSidebarPlaceholderForCurrentSession();
    }

    /** 当前 sessionId 若不在侧栏列表中，则插入占位项并渲染（与首条消息发送后的 save 逻辑一致）。 */
    ensureSidebarPlaceholderForCurrentSession() {
        if (!this.sessionId) {
            return;
        }
        const idx = this.chatHistories.findIndex((h) => h.id === this.sessionId);
        if (idx === -1) {
            const now = new Date().toISOString();
            this.chatHistories.unshift({
                id: this.sessionId,
                title: '新对话',
                messages: [],
                createdAt: now,
                updatedAt: now
            });
            if (this.chatHistories.length > 50) {
                this.chatHistories = this.chatHistories.slice(0, 50);
            }
            this.saveChatHistories();
        }
        this.renderChatHistory();
    }
    
    // 保存当前对话到历史记录（新建）
    saveCurrentChat() {
        if (this.currentChatHistory.length === 0) {
            return;
        }
        
        // 检查是否已存在相同ID的历史记录
        const existingIndex = this.chatHistories.findIndex(h => h.id === this.sessionId);
        if (existingIndex !== -1) {
            // 如果已存在，更新而不是新建
            this.updateCurrentChatHistory();
            return;
        }
        
        // 获取对话标题（使用第一条用户消息的前30个字符）
        const firstUserMessage = this.currentChatHistory.find(msg => msg.type === 'user');
        const title = firstUserMessage ? 
            (firstUserMessage.content.substring(0, 30) + (firstUserMessage.content.length > 30 ? '...' : '')) : 
            '新对话';
        
        const chatHistory = {
            id: this.sessionId,
            title: title,
            messages: [...this.currentChatHistory],
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString()
        };
        
        // 添加到历史记录列表的开头
        this.chatHistories.unshift(chatHistory);
        
        // 限制历史记录数量（最多保存50条）
        if (this.chatHistories.length > 50) {
            this.chatHistories = this.chatHistories.slice(0, 50);
        }
        
        // 保存到localStorage
        this.saveChatHistories();
    }
    
    // 更新当前对话的历史记录
    updateCurrentChatHistory() {
        if (this.currentChatHistory.length === 0) {
            return;
        }
        
        const existingIndex = this.chatHistories.findIndex(h => h.id === this.sessionId);
        if (existingIndex === -1) {
            // 如果不存在，调用保存方法
            this.saveCurrentChat();
            return;
        }
        
        // 从原位置取出再插到顶部：合并/排序后占位可能在列表下方，用户以为「没出现」
        const history = this.chatHistories.splice(existingIndex, 1)[0];
        history.messages = [...this.currentChatHistory];
        history.updatedAt = new Date().toISOString();

        const firstUserMessage = this.currentChatHistory.find(msg => msg.type === 'user');
        if (firstUserMessage) {
            history.title = firstUserMessage.content.substring(0, 30) + (firstUserMessage.content.length > 30 ? '...' : '');
        }
        this.chatHistories.unshift(history);
        this.saveChatHistories();
    }
    
    // 加载历史对话列表
    loadChatHistories() {
        try {
            const stored = localStorage.getItem('chatHistories');
            return stored ? JSON.parse(stored) : [];
        } catch (e) {
            console.error('加载历史对话失败:', e);
            return [];
        }
    }

    async fetchServerSessions() {
        const res = await fetch(`${this.apiBaseUrl}/chat/sessions`);
        if (!res.ok) {
            return [];
        }
        const json = await res.json();
        if (!json || json.code !== 200 || !json.data || !Array.isArray(json.data.sessions)) {
            return [];
        }
        return json.data.sessions.map((s) => ({
            id: s.id,
            title: s.title || '新对话',
            messages: [],
            createdAt: new Date(s.updatedAt || Date.now()).toISOString(),
            updatedAt: new Date(s.updatedAt || Date.now()).toISOString()
        }));
    }

    mergeChatHistories(server, local) {
        const map = new Map();
        for (const h of local) {
            if (!h || h.id == null || h.id === '') {
                continue;
            }
            map.set(h.id, {
                ...h,
                messages: Array.isArray(h.messages) ? [...h.messages] : []
            });
        }
        for (const s of server) {
            if (!s || s.id == null || s.id === '') {
                continue;
            }
            const existing = map.get(s.id);
            if (!existing) {
                map.set(s.id, { ...s });
            } else {
                const tServer = new Date(s.updatedAt || 0).getTime();
                const tLocal = new Date(existing.updatedAt || 0).getTime();
                if (tServer >= tLocal) {
                    existing.updatedAt = s.updatedAt;
                    existing.title = s.title || existing.title;
                }
            }
        }
        return Array.from(map.values()).sort(
            (a, b) => new Date(b.updatedAt) - new Date(a.updatedAt)
        );
    }

    async bootstrapHistoriesFromServer() {
        try {
            const server = await this.fetchServerSessions();
            if (!server.length) {
                return;
            }
            this.chatHistories = this.mergeChatHistories(server, this.chatHistories);
            this.saveChatHistories();
            this.renderChatHistory();
        } catch (e) {
            console.warn('从服务器合并会话列表失败:', e);
        }
    }
    
    // 保存历史对话列表到localStorage
    saveChatHistories() {
        try {
            localStorage.setItem('chatHistories', JSON.stringify(this.chatHistories));
        } catch (e) {
            console.error('保存历史对话失败:', e);
        }
    }
    
    // 渲染历史对话列表
    renderChatHistory() {
        if (!this.chatHistoryList) {
            return;
        }
        
        this.chatHistoryList.innerHTML = '';
        
        if (this.chatHistories.length === 0) {
            return;
        }
        
        this.chatHistories.forEach((history, index) => {
            const historyItem = document.createElement('div');
            historyItem.className = 'history-item';
            historyItem.dataset.historyId = history.id;
            
            historyItem.innerHTML = `
                <div class="history-item-content">
                    <span class="history-item-title">${this.escapeHtml(history.title)}</span>
                </div>
                <button class="history-item-delete" data-history-id="${history.id}" title="删除">
                    <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                        <path d="M18 6L6 18M6 6L18 18" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
                    </svg>
                </button>
            `;
            
            // 点击历史项加载对话
            historyItem.addEventListener('click', (e) => {
                if (!e.target.closest('.history-item-delete')) {
                    void this.loadChatHistory(history.id);
                }
            });
            
            // 删除历史对话
            const deleteBtn = historyItem.querySelector('.history-item-delete');
            deleteBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                void this.deleteChatHistory(history.id);
            });
            
            this.chatHistoryList.appendChild(historyItem);
        });
    }
    
    // 加载历史对话（优先从服务器恢复与 Agent 一致的消息记录）
    async loadChatHistory(historyId) {
        const history = this.chatHistories.find(h => h.id === historyId);
        if (!history) {
            return;
        }
        
        // 如果当前有对话内容，且不是同一个对话，先保存
        if (this.currentChatHistory.length > 0 && this.sessionId !== historyId) {
            if (this.isCurrentChatFromHistory) {
                // 如果当前对话也是从历史记录加载的，更新它
                this.updateCurrentChatHistory();
            } else {
                // 如果当前对话是新对话，保存为新历史
                this.saveCurrentChat();
            }
        }
        
        let messages = null;
        try {
            const res = await fetch(`${this.apiBaseUrl}/chat/messages/${encodeURIComponent(historyId)}`);
            const data = await res.json();
            if (data.code === 200 && data.data && Array.isArray(data.data.messages) && data.data.messages.length > 0) {
                messages = data.data.messages.map((m) => ({
                    type: m.role === 'user' ? 'user' : 'assistant',
                    content: m.content || ''
                }));
            }
        } catch (e) {
            console.warn('从服务器加载会话消息失败:', e);
        }
        if (!messages || messages.length === 0) {
            messages = history.messages && history.messages.length ? [...history.messages] : [];
        }

        // 加载历史对话
        this.sessionId = history.id;
        Auto206AgentApp.persistSessionId(this.sessionId);
        this.currentChatHistory = [...messages];
        this.isCurrentChatFromHistory = true; // 标记为从历史记录加载
        
        // 清空并重新渲染消息
        if (this.chatMessages) {
            this.chatMessages.innerHTML = '';
            messages.forEach(msg => {
                this.addMessage(msg.type, msg.content, false, false); // false表示不是流式，false表示不保存到历史（因为已经存在）
            });
        }
        
        // 更新UI
        this.checkAndSetCentered();
        this.renderChatHistory();
    }
    
    // 删除历史对话（先打开自定义确认弹层）
    deleteChatHistory(historyId) {
        this.openDeleteSessionConfirmModal(historyId);
    }

    openDeleteSessionConfirmModal(historyId) {
        if (!this.deleteSessionConfirmModal) {
            return;
        }
        this.pendingDeleteHistoryId = historyId;
        const hist = this.chatHistories.find((h) => h.id === historyId);
        if (this.deleteSessionConfirmName) {
            if (hist && hist.title) {
                this.deleteSessionConfirmName.textContent = `会话标题：${hist.title}`;
            } else {
                this.deleteSessionConfirmName.textContent = '';
            }
        }
        this.deleteSessionConfirmModal.style.display = 'flex';
        this.deleteSessionConfirmModal.setAttribute('aria-hidden', 'false');
        this._deleteConfirmOnKeydown = (e) => {
            if (e.key === 'Escape') {
                this.closeDeleteSessionConfirmModal();
            }
        };
        document.addEventListener('keydown', this._deleteConfirmOnKeydown);
        if (this.deleteSessionConfirmCancel) {
            this.deleteSessionConfirmCancel.focus();
        }
    }

    closeDeleteSessionConfirmModal() {
        if (this._deleteConfirmOnKeydown) {
            document.removeEventListener('keydown', this._deleteConfirmOnKeydown);
            this._deleteConfirmOnKeydown = null;
        }
        this.pendingDeleteHistoryId = null;
        if (this.deleteSessionConfirmModal) {
            this.deleteSessionConfirmModal.style.display = 'none';
            this.deleteSessionConfirmModal.setAttribute('aria-hidden', 'true');
        }
    }

    async confirmDeleteSession() {
        const historyId = this.pendingDeleteHistoryId;
        this.closeDeleteSessionConfirmModal();
        if (!historyId) {
            return;
        }
        await this.performDeleteChatHistory(historyId);
    }

    /** 执行删除（无二次确认） */
    async performDeleteChatHistory(historyId) {
        try {
            await fetch(`${this.apiBaseUrl}/chat/session/${encodeURIComponent(historyId)}`, { method: 'DELETE' });
        } catch (e) {
            console.warn('删除服务器会话失败:', e);
        }
        this.chatHistories = this.chatHistories.filter((h) => h.id !== historyId);
        this.saveChatHistories();
        this.renderChatHistory();

        if (this.sessionId === historyId) {
            this.currentChatHistory = [];
            if (this.chatMessages) {
                this.chatMessages.innerHTML = '';
            }
            this.sessionId = this.generateSessionId();
            Auto206AgentApp.persistSessionId(this.sessionId);
            this.checkAndSetCentered();
        }
    }

    // 切换模式下拉菜单
    toggleModeDropdown() {
        if (this.modeSelectorBtn && this.modeDropdown) {
            const wrapper = this.modeSelectorBtn.closest('.mode-selector-wrapper');
            if (wrapper) {
                wrapper.classList.toggle('active');
            }
        }
    }

    // 关闭模式下拉菜单
    closeModeDropdown() {
        if (this.modeSelectorBtn && this.modeDropdown) {
            const wrapper = this.modeSelectorBtn.closest('.mode-selector-wrapper');
            if (wrapper) {
                wrapper.classList.remove('active');
            }
        }
    }

    // 选择模式
    selectMode(mode) {
        if (this.isStreaming) {
            this.showNotification('请等待当前对话完成后再切换模式', 'warning');
            return;
        }
        
        this.currentMode = mode;
        this.updateUI();
        
        const modeNames = {
            'quick': '快速',
            'stream': '流式'
        };
        
        this.showNotification(`已切换到${modeNames[mode]}模式`, 'info');
    }

    // 更新UI
    updateUI() {
        // 更新模式选择器显示
        if (this.currentModeText) {
            const modeNames = {
                'quick': '快速',
                'stream': '流式'
            };
            this.currentModeText.textContent = modeNames[this.currentMode] || '快速';
        }
        
        // 更新下拉菜单选中状态
        const dropdownItems = document.querySelectorAll('.dropdown-item');
        dropdownItems.forEach(item => {
            const mode = item.getAttribute('data-mode');
            if (mode === this.currentMode) {
                item.classList.add('active');
            } else {
                item.classList.remove('active');
            }
        });
        
        // 更新发送按钮状态
        if (this.sendButton) {
            this.sendButton.disabled = this.isStreaming;
        }
        
        // 更新输入框状态
        if (this.messageInput) {
            this.messageInput.disabled = this.isStreaming;
            this.messageInput.placeholder = '问问 Auto206Agent';
        }
    }

    // 生成随机会话ID
    generateSessionId() {
        return 'session_' + Math.random().toString(36).substr(2, 9) + '_' + Date.now();
    }

    // 发送消息
    async sendMessage() {
        let message = '';
        if (this.messageInput) {
            message = this.messageInput.value.trim();
        }
        const imagesSnapshot = (this.pendingChatImages || []).map((x) => ({ mimeType: x.mimeType, data: x.data }));

        if (!message && imagesSnapshot.length === 0) {
            this.showNotification('请输入文字或添加至少一张图片', 'warning');
            return;
        }

        if (this.isStreaming) {
            this.showNotification('请等待当前对话完成', 'warning');
            return;
        }

        Auto206AgentApp.persistSessionId(this.sessionId);

        const displayUser = message
            ? (imagesSnapshot.length ? `${message} [附图${imagesSnapshot.length}张]` : message)
            : `（图片） [附图${imagesSnapshot.length}张]`;
        this.addMessage('user', displayUser);

        this.pendingChatImages = [];
        this.renderChatImagePreviewBar();
        if (this.messageInput) {
            this.messageInput.value = '';
        }

        const requestBody = {
            Id: this.sessionId,
            Question: message || '请结合图片回答。',
        };
        if (imagesSnapshot.length > 0) {
            requestBody.Images = imagesSnapshot.map((x) => ({ MimeType: x.mimeType, Data: x.data }));
        }

        // 设置发送状态
        this.isStreaming = true;
        this.updateUI();

        try {
            if (this.currentMode === 'quick') {
                await this.sendQuickMessage(requestBody);
            } else if (this.currentMode === 'stream') {
                await this.sendStreamMessage(requestBody);
            }
        } catch (error) {
            console.error('发送消息失败:', error);
            this.addMessage('assistant', '抱歉，发送消息时出现错误：' + error.message);
        } finally {
            this.isStreaming = false;
            this.updateUI();

            // 同步侧栏：新建对话（非历史）此前未调用 save，导致列表不更新
            if (this.currentChatHistory.length > 0) {
                if (this.isCurrentChatFromHistory) {
                    this.updateCurrentChatHistory();
                } else {
                    this.saveCurrentChat();
                }
                this.renderChatHistory();
            }
        }
    }

    // 发送快速消息（普通对话）
    async sendQuickMessage(requestBody) {
        // 添加等待提示消息
        const loadingMessage = this.addLoadingMessage('正在思考...');
        
        try {
            const response = await fetch(`${this.apiBaseUrl}/chat`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify(requestBody)
            });

            if (!response.ok) {
                throw new Error(`HTTP错误: ${response.status}`);
            }

            const data = await response.json();
            console.log('[sendQuickMessage] 响应数据:', JSON.stringify(data));
            
            // 移除等待提示消息
            if (loadingMessage && loadingMessage.parentNode) {
                loadingMessage.parentNode.removeChild(loadingMessage);
            }
            
            // 统一响应格式：检查 data.code 或 data.message 判断请求是否成功
            if (data.code === 200 || data.message === 'success') {
                // data.data 是 ChatResponse 对象
                const chatResponse = data.data;
                
                if (chatResponse && chatResponse.success) {
                    // 成功：添加实际响应消息（即使 answer 为空也显示）
                    const answer = chatResponse.answer || '（无回复内容）';
                    this.addMessage('assistant', answer);
                } else if (chatResponse && chatResponse.errorMessage) {
                    // 业务错误
                    throw new Error(chatResponse.errorMessage);
                } else {
                    // 兜底：尝试显示任何可用内容
                    const fallbackAnswer = chatResponse?.answer || chatResponse?.errorMessage || '服务返回了空内容';
                    this.addMessage('assistant', fallbackAnswer);
                }
            } else {
                // HTTP 成功但业务失败
                throw new Error(data.message || '请求失败');
            }
        } catch (error) {
            // 出错时也要移除等待提示消息
            if (loadingMessage && loadingMessage.parentNode) {
                loadingMessage.parentNode.removeChild(loadingMessage);
            }
            throw error;
        }
    }

    // 发送流式消息
    async sendStreamMessage(requestBody) {
        const loadingMessage = this.addLoadingMessage('正在思考...');
        let assistantMessageElement = null;
        let thinkingCleared = false;
        const clearThinkingOnce = () => {
            if (thinkingCleared || !assistantMessageElement) {
                return;
            }
            thinkingCleared = true;
            this.clearThinkingStateFromAssistantBubble(assistantMessageElement);
        };

        try {
            const response = await fetch(`${this.apiBaseUrl}/chat_stream`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify(requestBody)
            });

            if (!response.ok) {
                throw new Error(`HTTP错误: ${response.status}`);
            }

            if (loadingMessage && loadingMessage.parentNode) {
                loadingMessage.parentNode.removeChild(loadingMessage);
            }

            assistantMessageElement = this.addMessage('assistant', '', true);
            this.applyThinkingStateToAssistantBubble(assistantMessageElement);
            let fullResponse = '';

            // 处理流式响应
            const reader = response.body.getReader();
            const decoder = new TextDecoder();
            let buffer = '';
            let currentEvent = '';

            try {
                while (true) {
                    const { done, value } = await reader.read();
                    
                    if (done) {
                        // 流结束，使用统一的处理方法
                        this.handleStreamComplete(assistantMessageElement, fullResponse);
                        break;
                    }

                    // 解码数据并添加到缓冲区
                    buffer += decoder.decode(value, { stream: true });
                    
                    // 按行分割处理
                    const lines = buffer.split('\n');
                    // 保留最后一行（可能不完整）
                    buffer = lines.pop() || '';
                    
                    for (const line of lines) {
                        if (line.trim() === '') continue;
                        
                        console.log('[SSE调试] 收到行:', line);
                        
                        // 解析SSE格式
                        if (line.startsWith('id:')) {
                            console.log('[SSE调试] 解析到ID');
                            continue;
                        } else if (line.startsWith('event:')) {
                            // 兼容 "event:message" 和 "event: message" 两种格式
                            currentEvent = line.substring(6).trim();
                            console.log('[SSE调试] 解析到事件类型:', currentEvent);
                            // 注意：后端统一使用 "message" 事件名，真正的类型在 data 的 JSON 中
                            continue;
                        } else if (line.startsWith('data:')) {
                            // 兼容 "data:xxx" 和 "data: xxx" 两种格式
                            const rawData = line.substring(5).trim();
                            console.log('[SSE调试] 解析到数据, currentEvent:', currentEvent, ', rawData:', rawData);
                            
                            // 兼容旧格式 [DONE] 标记
                            if (rawData === '[DONE]') {
                                // 流结束标记，将内容转换为Markdown渲染
                                this.handleStreamComplete(assistantMessageElement, fullResponse);
                                return;
                            }
                            
                            // 处理 SSE 数据
                            try {
                                // 尝试解析为 SseMessage 格式的 JSON
                                const sseMessage = JSON.parse(rawData);
                                console.log('[SSE调试] 解析JSON成功:', sseMessage);
                                
                                if (sseMessage && typeof sseMessage.type === 'string') {
                                    if (sseMessage.type === 'content') {
                                        const content = sseMessage.data || '';
                                        fullResponse += content;
                                        console.log('[SSE调试] 添加内容:', content);
                                        if (content.length > 0) {
                                            clearThinkingOnce();
                                        }
                                        // 实时渲染 Markdown
                                        if (assistantMessageElement) {
                                            const messageContent = assistantMessageElement.querySelector('.message-content');
                                            messageContent.innerHTML = this.renderMarkdown(fullResponse);
                                            // 高亮代码块
                                            this.highlightCodeBlocks(messageContent);
                                            this.scrollToBottom();
                                        }
                                    } else if (sseMessage.type === 'done') {
                                        console.log('[SSE调试] 收到done标记，流结束');
                                        this.handleStreamComplete(assistantMessageElement, fullResponse);
                                        return;
                                    } else if (sseMessage.type === 'error') {
                                        console.error('[SSE调试] 收到错误:', sseMessage.data);
                                        if (assistantMessageElement) {
                                            clearThinkingOnce();
                                            const messageContent = assistantMessageElement.querySelector('.message-content');
                                            messageContent.innerHTML = this.renderMarkdown('错误: ' + (sseMessage.data || '未知错误'));
                                        }
                                        return;
                                    }
                                } else {
                                    // 不是标准 SseMessage 格式，尝试兼容处理
                                    console.log('[SSE调试] 非标准格式，尝试兼容处理');
                                    fullResponse += rawData;
                                    if (rawData.length > 0) {
                                        clearThinkingOnce();
                                    }
                                    if (assistantMessageElement) {
                                        const messageContent = assistantMessageElement.querySelector('.message-content');
                                        messageContent.innerHTML = this.renderMarkdown(fullResponse);
                                        this.highlightCodeBlocks(messageContent);
                                        this.scrollToBottom();
                                    }
                                }
                            } catch (e) {
                                // JSON 解析失败，尝试兼容旧格式
                                console.log('[SSE调试] JSON解析失败，使用兼容模式:', e.message);
                                if (rawData === '') {
                                    fullResponse += '\n';
                                } else {
                                    fullResponse += rawData;
                                }
                                if (rawData.length > 0 || fullResponse.trim().length > 0) {
                                    clearThinkingOnce();
                                }
                                if (assistantMessageElement) {
                                    const messageContent = assistantMessageElement.querySelector('.message-content');
                                    messageContent.innerHTML = this.renderMarkdown(fullResponse);
                                    this.highlightCodeBlocks(messageContent);
                                    this.scrollToBottom();
                                }
                            }
                        }
                    }
                }
            } finally {
                reader.releaseLock();
            }
        } catch (error) {
            if (loadingMessage && loadingMessage.parentNode) {
                loadingMessage.parentNode.removeChild(loadingMessage);
            }
            if (assistantMessageElement && assistantMessageElement.parentNode) {
                this.clearThinkingStateFromAssistantBubble(assistantMessageElement);
                const messageContent = assistantMessageElement.querySelector('.message-content');
                const stillEmpty = !messageContent
                    || (!messageContent.innerHTML || messageContent.innerHTML.trim() === '');
                if (stillEmpty) {
                    assistantMessageElement.parentNode.removeChild(assistantMessageElement);
                }
            }
            throw error;
        }
    }

    // 添加消息到聊天界面
    addMessage(type, content, isStreaming = false, saveToHistory = true) {
        // 检查是否是第一条消息，如果是则移除居中样式
        const isFirstMessage = this.chatMessages && this.chatMessages.querySelectorAll('.message').length === 0;
        
        // 保存消息到当前对话历史（如果不是流式消息且需要保存）
        if (!isStreaming && saveToHistory && content) {
            this.currentChatHistory.push({
                type: type,
                content: content,
                timestamp: new Date().toISOString()
            });
        }
        
        const messageDiv = document.createElement('div');
        messageDiv.className = `message ${type}${isStreaming ? ' streaming' : ''}`;

        // 如果是assistant消息，添加头像图标
        if (type === 'assistant') {
            const messageAvatar = document.createElement('div');
            messageAvatar.className = 'message-avatar';
            messageAvatar.innerHTML = `
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <path d="M12 2L15.09 8.26L22 9.27L17 14.14L18.18 21.02L12 17.77L5.82 21.02L7 14.14L2 9.27L8.91 8.26L12 2Z" fill="white"/>
                </svg>
            `;
            messageDiv.appendChild(messageAvatar);
        }

        // 创建消息内容包装器
        const messageContentWrapper = document.createElement('div');
        messageContentWrapper.className = 'message-content-wrapper';

        const messageContent = document.createElement('div');
        messageContent.className = 'message-content';
        
        // 如果是assistant消息且不是流式消息，使用Markdown渲染
        if (type === 'assistant' && !isStreaming) {
            messageContent.innerHTML = this.renderMarkdown(content);
            // 高亮代码块
            this.highlightCodeBlocks(messageContent);
        } else {
            // 用户消息或流式消息使用纯文本
            messageContent.textContent = content;
        }

        messageContentWrapper.appendChild(messageContent);
        messageDiv.appendChild(messageContentWrapper);

        if (this.chatMessages) {
            this.chatMessages.appendChild(messageDiv);
            
            // 如果是第一条消息，移除居中样式并添加动画
            if (isFirstMessage && this.chatContainer) {
                this.chatContainer.classList.remove('centered');
                // 添加动画类
                this.chatContainer.style.transition = 'all 0.5s ease';
            }
            
            this.scrollToBottom();
        }

        return messageDiv;
    }

    // 添加带加载动画的消息
    addLoadingMessage(content) {
        const messageDiv = document.createElement('div');
        messageDiv.className = 'message assistant';

        // 添加头像图标
        const messageAvatar = document.createElement('div');
        messageAvatar.className = 'message-avatar';
        messageAvatar.innerHTML = `
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path d="M12 2L15.09 8.26L22 9.27L17 14.14L18.18 21.02L12 17.77L5.82 21.02L7 14.14L2 9.27L8.91 8.26L12 2Z" fill="white"/>
            </svg>
        `;
        messageDiv.appendChild(messageAvatar);

        // 创建消息内容包装器
        const messageContentWrapper = document.createElement('div');
        messageContentWrapper.className = 'message-content-wrapper';

        const messageContent = document.createElement('div');
        messageContent.className = 'message-content loading-message-content';
        
        // 创建文本和动画容器
        const textSpan = document.createElement('span');
        textSpan.textContent = content;
        
        // 创建旋转动画图标
        const loadingIcon = document.createElement('span');
        loadingIcon.className = 'loading-spinner-icon';
        loadingIcon.innerHTML = `
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z" fill="currentColor" opacity="0.2"/>
                <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10c1.54 0 3-.36 4.28-1l-1.5-2.6C13.64 19.62 12.84 20 12 20c-4.41 0-8-3.59-8-8s3.59-8 8-8c.84 0 1.64.38 2.18 1l1.5-2.6C13 2.36 12.54 2 12 2z" fill="currentColor"/>
            </svg>
        `;
        
        messageContent.appendChild(textSpan);
        messageContent.appendChild(loadingIcon);
        messageContentWrapper.appendChild(messageContent);
        messageDiv.appendChild(messageContentWrapper);

        if (this.chatMessages) {
            this.chatMessages.appendChild(messageDiv);
            
            // 如果是第一条消息，移除居中样式
            const isFirstMessage = this.chatMessages.querySelectorAll('.message').length === 1;
            if (isFirstMessage && this.chatContainer) {
                this.chatContainer.classList.remove('centered');
                this.chatContainer.style.transition = 'all 0.5s ease';
            }
            
            this.scrollToBottom();
        }

        return messageDiv;
    }

    /** 流式助手气泡在首字前：与 addLoadingMessage 同款的「正在思考…」+ 转圈 */
    applyThinkingStateToAssistantBubble(messageDiv) {
        const messageContent = messageDiv && messageDiv.querySelector('.message-content');
        if (!messageContent) {
            return;
        }
        messageContent.dataset.thinkingActive = '1';
        messageContent.classList.add('loading-message-content');
        messageContent.replaceChildren();
        const textSpan = document.createElement('span');
        textSpan.textContent = '正在思考...';
        const loadingIcon = document.createElement('span');
        loadingIcon.className = 'loading-spinner-icon';
        loadingIcon.innerHTML = `
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z" fill="currentColor" opacity="0.2"/>
                <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10c1.54 0 3-.36 4.28-1l-1.5-2.6C13.64 19.62 12.84 20 12 20c-4.41 0-8-3.59-8-8s3.59-8 8-8c.84 0 1.64.38 2.18 1l1.5-2.6C13 2.36 12.54 2 12 2z" fill="currentColor"/>
            </svg>
        `;
        messageContent.appendChild(textSpan);
        messageContent.appendChild(loadingIcon);
    }

    /** 首字流出前调用；可重复调用（仅当 thinkingActive 时生效） */
    clearThinkingStateFromAssistantBubble(messageDiv) {
        const messageContent = messageDiv && messageDiv.querySelector('.message-content');
        if (!messageContent || messageContent.dataset.thinkingActive !== '1') {
            return;
        }
        delete messageContent.dataset.thinkingActive;
        messageContent.classList.remove('loading-message-content');
        messageContent.replaceChildren();
    }
    
    // 检查并设置居中样式
    checkAndSetCentered() {
        if (this.chatMessages && this.chatContainer) {
            const hasMessages = this.chatMessages.querySelectorAll('.message').length > 0;
            if (!hasMessages) {
                this.chatContainer.classList.add('centered');
            } else {
                this.chatContainer.classList.remove('centered');
            }
        }
    }

    // 滚动到底部
    scrollToBottom() {
        if (this.chatMessages) {
            this.chatMessages.scrollTop = this.chatMessages.scrollHeight;
        }
    }

    // 处理流式传输完成
    handleStreamComplete(assistantMessageElement, fullResponse) {
        if (assistantMessageElement) {
            this.clearThinkingStateFromAssistantBubble(assistantMessageElement);
            assistantMessageElement.classList.remove('streaming');
            const messageContent = assistantMessageElement.querySelector('.message-content');
            if (messageContent) {
                messageContent.innerHTML = this.renderMarkdown(fullResponse);
                // 高亮代码块
                this.highlightCodeBlocks(messageContent);
            }
        }
        // 保存流式消息到历史记录
        if (fullResponse) {
            this.currentChatHistory.push({
                type: 'assistant',
                content: fullResponse,
                timestamp: new Date().toISOString()
            });
            if (this.isCurrentChatFromHistory) {
                this.updateCurrentChatHistory();
            } else {
                this.saveCurrentChat();
            }
            this.renderChatHistory();
        }
    }

    // 显示通知
    showNotification(message, type = 'info') {
        // 创建通知元素
        const notification = document.createElement('div');
        notification.className = `notification ${type}`;
        notification.textContent = message;
        notification.style.cssText = `
            position: fixed;
            top: 20px;
            right: 20px;
            padding: 15px 20px;
            border-radius: 8px;
            color: #4a4544;
            font-weight: 500;
            z-index: 10000;
            animation: slideIn 0.3s ease;
            max-width: 300px;
            border: 1px solid rgba(255,192,203,0.45);
            box-shadow: 0 8px 24px rgba(192,217,175,0.25);
        `;

        const colors = {
            info: 'linear-gradient(135deg, #B2EBF2 0%, #C0D9AF 100%)',
            success: 'linear-gradient(135deg, #C0D9AF 0%, #B2EBF2 100%)',
            warning: 'linear-gradient(135deg, #FFE4B5 0%, #FFC0CB 100%)',
            error: 'linear-gradient(135deg, #FFC0CB 0%, #F5B7B1 100%)'
        };
        notification.style.background = colors[type] || colors.info;

        // 添加到页面
        document.body.appendChild(notification);

        // 3秒后自动移除
        setTimeout(() => {
            notification.style.animation = 'slideOut 0.3s ease';
            setTimeout(() => {
                if (notification.parentNode) {
                    notification.parentNode.removeChild(notification);
                }
            }, 300);
        }, 3000);
    }

    // 处理文件选择
    handleFileSelect(event) {
        const file = event.target.files[0];
        if (file) {
            // 验证文件格式
            if (!this.validateFileType(file)) {
                this.showNotification('只支持上传 TXT、Markdown、DOC、DOCX 或 PDF 格式的文件', 'error');
                this.fileInput.value = '';
                return;
            }
            this.uploadFile(file);
        }
    }

    // 验证文件类型
    validateFileType(file) {
        const fileName = file.name.toLowerCase();
        const allowedExtensions = ['.txt', '.md', '.markdown', '.doc', '.docx', '.pdf'];
        return allowedExtensions.some(ext => fileName.endsWith(ext));
    }

    // 上传文件到知识库
    async uploadFile(file) {
        // 再次验证文件类型（双重保险）
        if (!this.validateFileType(file)) {
            this.showNotification('只支持上传 TXT、Markdown、DOC、DOCX 或 PDF 格式的文件', 'error');
            return;
        }

        // 验证文件大小（限制为50MB）
        const maxSize = 50 * 1024 * 1024;
        if (file.size > maxSize) {
            this.showNotification('文件大小不能超过50MB', 'error');
            return;
        }

        // 锁定前端并显示上传遮罩层
        this.isStreaming = true;
        this.updateUI();
        this.showUploadOverlay(true, file.name);

        try {
            // 创建 FormData
            const formData = new FormData();
            formData.append('file', file);
            if (this.sessionId) {
                formData.append('sessionId', this.sessionId);
            }

            // 发送上传请求
            const response = await fetch(`${this.apiBaseUrl}/upload`, {
                method: 'POST',
                body: formData
            });

            if (!response.ok) {
                throw new Error(`HTTP错误: ${response.status}`);
            }

            const data = await response.json();

            if ((data.code === 200 || data.message === 'success') && data.data) {
                Auto206AgentApp.persistSessionId(this.sessionId);
                // 在聊天界面显示上传成功消息（会话与检索绑定，勿在刷新/未提示下新建对话后仍用旧上传）
                const successMessage = `${file.name} 上传到知识库成功。\n提示：已绑定当前对话会话；请在本对话中提问。若刷新页面，会话会自动恢复；若点击「新建对话」，需重新上传文件才能在新对话中检索到。`;
                this.addMessage('assistant', successMessage, false, true);
            } else {
                throw new Error(data.message || '上传失败');
            }
        } catch (error) {
            console.error('文件上传失败:', error);
            this.showNotification('文件上传失败: ' + error.message, 'error');
        } finally {
            // 清空文件输入
            if (this.fileInput) {
                this.fileInput.value = '';
            }
            // 解锁前端
            this.isStreaming = false;
            this.showUploadOverlay(false);
            this.updateUI();
        }
    }

    // 格式化文件大小
    formatFileSize(bytes) {
        if (bytes === 0) return '0 Bytes';
        const k = 1024;
        const sizes = ['Bytes', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return Math.round(bytes / Math.pow(k, i) * 100) / 100 + ' ' + sizes[i];
    }

    // 发送智能运维请求（SSE 流式模式）
    async sendAIOpsRequest(loadingMessageElement) {
        try {
            const response = await fetch(`${this.apiBaseUrl}/ai_ops`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                }
            });

            if (!response.ok) {
                throw new Error(`HTTP错误: ${response.status}`);
            }

            let fullResponse = '';

            // 处理 SSE 流式响应
            const reader = response.body.getReader();
            const decoder = new TextDecoder();
            let buffer = '';
            let currentEvent = 'message'; // 默认事件类型为 message

            try {
                while (true) {
                    const { done, value } = await reader.read();
                    
                    if (done) {
                        // 流结束，更新最终内容
                        if (fullResponse) {
                            console.log('AI Ops 流结束，更新最终内容，长度:', fullResponse.length);
                            this.updateAIOpsMessage(loadingMessageElement, fullResponse, []);
                        }
                        break;
                    }

                    // 解码数据并添加到缓冲区
                    buffer += decoder.decode(value, { stream: true });
                    
                    // 按行分割处理
                    const lines = buffer.split('\n');
                    // 保留最后一行（可能不完整）
                    buffer = lines.pop() || '';
                    
                    for (const line of lines) {
                        if (line.trim() === '') continue;
                        
                        console.log('[AI Ops SSE] 收到行:', line);
                        
                        // 解析 SSE 格式
                        if (line.startsWith('id:')) {
                            continue;
                        } else if (line.startsWith('event:')) {
                            currentEvent = line.substring(6).trim();
                            console.log('[AI Ops SSE] 事件类型:', currentEvent);
                            continue;
                        } else if (line.startsWith('data:')) {
                            const rawData = line.substring(5).trim();
                            console.log('[AI Ops SSE] 数据:', rawData, ', currentEvent:', currentEvent);
                            
                            // 解析可能包含多个JSON对象的数据
                            const processJsonMessages = (data) => {
                                const jsonPattern = /\{"type"\s*:\s*"[^"]+"\s*,\s*"data"\s*:\s*(?:"[^"]*"|null)\}/g;
                                const matches = data.match(jsonPattern);
                                
                                if (matches && matches.length > 0) {
                                    console.log('[AI Ops SSE] 匹配到', matches.length, '个JSON对象');
                                    for (const jsonStr of matches) {
                                        try {
                                            const sseMessage = JSON.parse(jsonStr);
                                            if (sseMessage.type === 'content') {
                                                fullResponse += sseMessage.data || '';
                                            } else if (sseMessage.type === 'done') {
                                                console.log('AI Ops 流完成，最终内容长度:', fullResponse.length);
                                                this.updateAIOpsMessage(loadingMessageElement, fullResponse, []);
                                                return true;
                                            } else if (sseMessage.type === 'error') {
                                                throw new Error(sseMessage.data || '智能运维分析失败');
                                            }
                                        } catch (e) {
                                            if (e.message.includes('智能运维')) throw e;
                                            console.log('[AI Ops SSE] 单个JSON解析失败:', jsonStr);
                                        }
                                    }
                                    if (loadingMessageElement) {
                                        this.updateAIOpsStreamContent(loadingMessageElement, fullResponse);
                                    }
                                    return false;
                                }
                                return null;
                            };
                            
                            const result = processJsonMessages(rawData);
                            if (result === true) {
                                return; // 流结束
                            } else if (result === null) {
                                // 没有匹配到多个JSON，尝试单个JSON解析
                                try {
                                    const sseMessage = JSON.parse(rawData);
                                    if (sseMessage && sseMessage.type) {
                                        if (sseMessage.type === 'content') {
                                            fullResponse += sseMessage.data || '';
                                            if (loadingMessageElement) {
                                                this.updateAIOpsStreamContent(loadingMessageElement, fullResponse);
                                            }
                                        } else if (sseMessage.type === 'done') {
                                            console.log('AI Ops 流完成，最终内容长度:', fullResponse.length);
                                            this.updateAIOpsMessage(loadingMessageElement, fullResponse, []);
                                            return;
                                        } else if (sseMessage.type === 'error') {
                                            throw new Error(sseMessage.data || '智能运维分析失败');
                                        }
                                    } else {
                                        fullResponse += rawData;
                                        if (loadingMessageElement) {
                                            this.updateAIOpsStreamContent(loadingMessageElement, fullResponse);
                                        }
                                    }
                                } catch (e) {
                                    if (e.message.includes('智能运维')) throw e;
                                    // 非 JSON 格式，直接追加原始数据
                                    fullResponse += rawData;
                                    if (loadingMessageElement) {
                                        this.updateAIOpsStreamContent(loadingMessageElement, fullResponse);
                                    }
                                }
                            }
                        }
                    }
                }
            } finally {
                reader.releaseLock();
            }
        } catch (error) {
            throw error;
        }
    }

    // 更新智能运维流式内容（实时显示）
    updateAIOpsStreamContent(messageElement, content) {
        if (!messageElement) return;
        
        // 添加 aiops-message 类
        messageElement.classList.add('aiops-message');
        
        const messageContentWrapper = messageElement.querySelector('.message-content-wrapper');
        if (messageContentWrapper) {
            let messageContent = messageContentWrapper.querySelector('.message-content');
            if (!messageContent) {
                messageContent = document.createElement('div');
                messageContent.className = 'message-content';
                messageContentWrapper.appendChild(messageContent);
            }
            // 流式显示时使用纯文本
            messageContent.textContent = content;
            this.scrollToBottom();
        }
    }

    // 更新智能运维消息（带折叠详情）
    updateAIOpsMessage(messageElement, response, details) {
        console.log('updateAIOpsMessage 被调用');
        console.log('messageElement:', messageElement);
        console.log('response:', response);
        console.log('response length:', response ? response.length : 0);
        console.log('details:', details);
        
        if (!messageElement) {
            // 如果没有传入消息元素，则创建新消息
            console.log('messageElement 为空，创建新消息');
            return this.addAIOpsMessage(response, details);
        }

        // 添加aiops-message类
        messageElement.classList.add('aiops-message');

        // 获取消息内容包装器
        const messageContentWrapper = messageElement.querySelector('.message-content-wrapper');
        if (!messageContentWrapper) {
            console.error('未找到 message-content-wrapper');
            return;
        }

        // 清空现有内容（保留消息内容容器）
        const messageContent = messageContentWrapper.querySelector('.message-content');
        if (!messageContent) {
            console.error('未找到 message-content');
            return;
        }

        // 移除加载动画相关的类和内容
        messageContent.classList.remove('loading-message-content');
        messageContent.textContent = '';
        
        // 移除加载图标（如果存在）
        const loadingIcon = messageContent.querySelector('.loading-spinner-icon');
        if (loadingIcon) {
            loadingIcon.remove();
        }

        // 详情部分（可折叠）- 先显示
        if (details && details.length > 0) {
            // 检查是否已存在详情容器
            let detailsContainer = messageElement.querySelector('.aiops-details');
            if (!detailsContainer) {
                detailsContainer = document.createElement('div');
                detailsContainer.className = 'aiops-details';
                messageContentWrapper.insertBefore(detailsContainer, messageContent);
            } else {
                // 清空现有详情
                detailsContainer.innerHTML = '';
            }

            const detailsToggle = document.createElement('div');
            detailsToggle.className = 'details-toggle';
            detailsToggle.innerHTML = `
                <svg class="toggle-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <path d="M9 18L15 12L9 6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
                <span>查看详细步骤 (${details.length}条)</span>
            `;

            const detailsContent = document.createElement('div');
            detailsContent.className = 'details-content';
            
            details.forEach((detail, index) => {
                const detailItem = document.createElement('div');
                detailItem.className = 'detail-item';
                detailItem.innerHTML = `<strong>步骤 ${index + 1}:</strong> ${this.escapeHtml(detail)}`;
                detailsContent.appendChild(detailItem);
            });

            // 点击切换折叠状态
            detailsToggle.addEventListener('click', () => {
                detailsContent.classList.toggle('expanded');
                detailsToggle.classList.toggle('expanded');
            });

            detailsContainer.appendChild(detailsToggle);
            detailsContainer.appendChild(detailsContent);
        }

        // 更新主要响应内容（使用Markdown渲染）
        console.log('开始渲染 Markdown');
        const renderedHtml = this.renderMarkdown(response);
        console.log('Markdown 渲染完成，HTML 长度:', renderedHtml ? renderedHtml.length : 0);
        messageContent.innerHTML = renderedHtml;
        console.log('innerHTML 已设置');
        // 高亮代码块
        this.highlightCodeBlocks(messageContent);
        console.log('代码块高亮完成');
        
        // 保存到历史记录
        this.currentChatHistory.push({
            type: 'assistant',
            content: response,
            timestamp: new Date().toISOString()
        });
        
        this.scrollToBottom();
        return messageElement;
    }

    // 添加智能运维消息（带折叠详情）- 保留用于兼容性
    addAIOpsMessage(response, details) {
        const messageDiv = document.createElement('div');
        messageDiv.className = 'message assistant aiops-message';

        // 添加头像图标
        const messageAvatar = document.createElement('div');
        messageAvatar.className = 'message-avatar';
        messageAvatar.innerHTML = `
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path d="M12 2L15.09 8.26L22 9.27L17 14.14L18.18 21.02L12 17.77L5.82 21.02L7 14.14L2 9.27L8.91 8.26L12 2Z" fill="white"/>
            </svg>
        `;
        messageDiv.appendChild(messageAvatar);

        // 创建消息内容包装器
        const messageContentWrapper = document.createElement('div');
        messageContentWrapper.className = 'message-content-wrapper';

        // 详情部分（可折叠）- 先显示
        if (details && details.length > 0) {
            const detailsContainer = document.createElement('div');
            detailsContainer.className = 'aiops-details';

            const detailsToggle = document.createElement('div');
            detailsToggle.className = 'details-toggle';
            detailsToggle.innerHTML = `
                <svg class="toggle-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <path d="M9 18L15 12L9 6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
                <span>查看详细步骤 (${details.length}条)</span>
            `;

            const detailsContent = document.createElement('div');
            detailsContent.className = 'details-content';
            
            details.forEach((detail, index) => {
                const detailItem = document.createElement('div');
                detailItem.className = 'detail-item';
                detailItem.innerHTML = `<strong>步骤 ${index + 1}:</strong> ${this.escapeHtml(detail)}`;
                detailsContent.appendChild(detailItem);
            });

            // 点击切换折叠状态
            detailsToggle.addEventListener('click', () => {
                detailsContent.classList.toggle('expanded');
                detailsToggle.classList.toggle('expanded');
            });

            detailsContainer.appendChild(detailsToggle);
            detailsContainer.appendChild(detailsContent);
            messageContentWrapper.appendChild(detailsContainer);
        }

        // 主要响应内容 - 后显示（使用Markdown渲染）
        const messageContent = document.createElement('div');
        messageContent.className = 'message-content';
        messageContent.innerHTML = this.renderMarkdown(response);
        // 高亮代码块
        this.highlightCodeBlocks(messageContent);
        messageContentWrapper.appendChild(messageContent);
        messageDiv.appendChild(messageContentWrapper);
        
        if (this.chatMessages) {
            this.chatMessages.appendChild(messageDiv);
            this.scrollToBottom();
        }

        return messageDiv;
    }

    // HTML转义
    escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    async openServerMonitorModal() {
        if (!this.serverMonitorModal || !this.serverMonitorBody) {
            return;
        }
        this.serverMonitorModal.style.display = 'flex';
        this.serverMonitorModal.setAttribute('aria-hidden', 'false');
        document.body.style.overflow = 'hidden';
        this.serverMonitorBody.innerHTML = '<p class="server-monitor-loading">正在拉取指标…</p>';
        await this.fetchServerMonitorSnapshot();
        this.startServerMonitorPolling();
    }

    closeServerMonitorModal() {
        if (!this.serverMonitorModal) {
            return;
        }
        this.stopServerMonitorPolling();
        this.serverMonitorModal.style.display = 'none';
        this.serverMonitorModal.setAttribute('aria-hidden', 'true');
        document.body.style.overflow = '';
    }

    startServerMonitorPolling() {
        this.stopServerMonitorPolling();
        const sec = Math.max(0, Number(this.serverMonitorRefreshSec) || 0);
        if (sec <= 0) {
            return;
        }
        this.serverMonitorPollTimer = setInterval(() => {
            void this.fetchServerMonitorSnapshot();
        }, sec * 1000);
    }

    stopServerMonitorPolling() {
        if (this.serverMonitorPollTimer) {
            clearInterval(this.serverMonitorPollTimer);
            this.serverMonitorPollTimer = null;
        }
    }

    /** 解析 UTC 的 Instant 字符串（可带纳秒）为 epoch 毫秒；避免部分环境 Date.parse 失败 */
    parseUtcInstantToEpochMs(iso) {
        if (iso == null) {
            return NaN;
        }
        let s = String(iso).trim();
        if (!s) {
            return NaN;
        }
        s = s.replace(/(\.\d{3})\d+/, '$1');
        let t = Date.parse(s);
        if (!Number.isNaN(t)) {
            return t;
        }
        const m = s.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?Z$/i);
        if (!m) {
            return NaN;
        }
        const frac = m[7] ? m[7].padEnd(3, '0').slice(0, 3) : '000';
        return Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6], +frac);
    }

    /** 将后端 ISO-8601（多为 UTC 带 Z）格式化为本机时区（兜底） */
    formatSnapshotTimeForDisplay(iso) {
        if (iso == null || String(iso).trim() === '') {
            return '';
        }
        const ms = this.parseUtcInstantToEpochMs(iso);
        if (Number.isNaN(ms)) {
            return String(iso).trim();
        }
        return new Date(ms).toLocaleString('zh-CN', { hour12: false, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' });
    }

    pickSnapshotField(d, camel, snake) {
        if (!d) {
            return '';
        }
        const a = d[camel];
        if (a != null && String(a).trim() !== '') {
            return String(a).trim();
        }
        const b = d[snake];
        return b != null && String(b).trim() !== '' ? String(b).trim() : '';
    }

    /** 监控弹层查询时间：优先服务端 queriedAtText，再 queriedAtDisplay+Zone，再解析 queriedAt（UTC） */
    formatServerMonitorQueriedAt(d) {
        const text = this.pickSnapshotField(d, 'queriedAtText', 'queried_at_text');
        if (text) {
            return text;
        }
        const disp = this.pickSnapshotField(d, 'queriedAtDisplay', 'queried_at_display');
        if (disp) {
            const z = this.pickSnapshotField(d, 'queriedAtZone', 'queried_at_zone');
            return z ? `${disp}（${z}）` : disp;
        }
        const raw = d && d.queriedAt != null ? String(d.queriedAt) : d && d.queried_at != null ? String(d.queried_at) : '';
        return this.formatSnapshotTimeForDisplay(raw);
    }

    formatBps(bytesPerSec) {
        if (bytesPerSec == null || Number.isNaN(bytesPerSec)) {
            return '—';
        }
        const u = ['B/s', 'KB/s', 'MB/s', 'GB/s'];
        let v = bytesPerSec;
        let i = 0;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return `${v < 10 && i > 0 ? v.toFixed(2) : v.toFixed(i === 0 ? 0 : 1)} ${u[i]}`;
    }

    /** 字节/秒 → 十进制兆比特/秒（Mb/s = B/s × 8 ÷ 10⁶），与常见带宽标称一致 */
    formatMbpsFromBytesPerSec(bytesPerSec) {
        if (bytesPerSec == null || Number.isNaN(bytesPerSec)) {
            return '—';
        }
        const mbps = (Number(bytesPerSec) * 8) / 1_000_000;
        if (mbps < 0.0001 && mbps > -0.0001) {
            return '0 Mb/s';
        }
        const decimals = mbps >= 100 ? 1 : mbps >= 10 ? 2 : 3;
        return `${mbps.toFixed(decimals)} Mb/s`;
    }

    renderServerMonitorTable(d) {
        const row = (label, valueHtml, err) => {
            const e = err ? `<div class="server-monitor-err">${this.escapeHtml(String(err))}</div>` : '';
            return `<tr><th>${this.escapeHtml(label)}</th><td>${valueHtml}${e}</td></tr>`;
        };
        const pct = (v) => (v == null ? '—' : `${this.escapeHtml(String(v))}%`);
        const num = (v) => (v == null ? '—' : this.escapeHtml(String(v)));
        const mock = d.mock ? '<p class="server-monitor-err">当前为 mock 演示数据（application.yml 中 server-monitor.mock-enabled=true）</p>' : '';
        const queriedLabel = this.formatServerMonitorQueriedAt(d);
        const meta = `<p style="font-size:12px;color:var(--text-muted);margin-bottom:12px">主机 <strong>${this.escapeHtml(String(d.host || ''))}</strong> · Prom <code style="font-size:11px">${this.escapeHtml(String(d.prometheusBaseUrl || ''))}</code><br>instance 正则 <code style="font-size:11px">${this.escapeHtml(String(d.instanceRegex || ''))}</code><br><strong>查询时间</strong> ${this.escapeHtml(queriedLabel)}</p>`;
        return `${mock}${meta}<table>
            ${row('CPU 使用率', pct(d.cpuPercent), d.cpuPercentError)}
            ${row('RAM 利用率', pct(d.memoryPercent), d.memoryPercentError)}
            ${row('平均负载 (1m)', num(d.load1), d.load1Error)}
            ${row('磁盘占用率', pct(d.diskRootPercent), d.diskRootPercentError)}
            ${row('网络下载速率', this.escapeHtml(this.formatMbpsFromBytesPerSec(d.networkReceiveBps)), d.networkReceiveBpsError)}
            ${row('网络上传速率', this.escapeHtml(this.formatMbpsFromBytesPerSec(d.networkTransmitBps)), d.networkTransmitBpsError)}
        </table>`;
    }

    async fetchServerMonitorSnapshot() {
        if (!this.serverMonitorBody) {
            return;
        }
        try {
            const res = await fetch(`${this.apiBaseUrl}/server-monitor/snapshot`);
            const json = await res.json();
            if (json.code !== 200 || !json.data) {
                this.serverMonitorBody.innerHTML = `<p class="server-monitor-err">${this.escapeHtml(json.message || '加载失败')}</p>`;
                return;
            }
            const d = json.data;
            if (typeof d.refreshSeconds === 'number' && this.serverMonitorRefreshHint) {
                this.serverMonitorRefreshSec = d.refreshSeconds;
                this.serverMonitorRefreshHint.textContent = String(d.refreshSeconds);
                if (d.refreshSeconds <= 0) {
                    this.stopServerMonitorPolling();
                }
            }
            this.serverMonitorBody.innerHTML = this.renderServerMonitorTable(d);
        } catch (e) {
            this.serverMonitorBody.innerHTML = `<p class="server-monitor-err">${this.escapeHtml(e.message || String(e))}</p>`;
        }
    }

    openResearchFeedModal() {
        if (!this.researchFeedModal || !this.researchFeedList) {
            return;
        }
        this.researchFeedModal.style.display = 'flex';
        this.researchFeedModal.setAttribute('aria-hidden', 'false');
        this.researchFeedList.innerHTML = '';
        this.researchFeedSessionId = null;
        if (this.researchFeedHint) {
            this.researchFeedHint.textContent = '点击下方按钮，从 arXiv 与 GitHub 拉取最近 7 天内与自动驾驶相关的论文与仓库（合并去重后随机展示）。「再来10篇」会替换当前列表为另一批随机条目。';
        }
        if (this.researchFeedMoreBtn) {
            this.researchFeedMoreBtn.disabled = true;
        }
    }

    closeResearchFeedModal() {
        if (!this.researchFeedModal) {
            return;
        }
        this.researchFeedModal.style.display = 'none';
        this.researchFeedModal.setAttribute('aria-hidden', 'true');
    }

    applyResearchFeedMeta(d) {
        if (!this.researchFeedHint || !d) {
            return;
        }
        const ws = d.weekStart ? String(d.weekStart).slice(0, 10) : '';
        const we = d.weekEnd ? String(d.weekEnd).slice(0, 10) : '';
        const rem = typeof d.remaining === 'number' ? d.remaining : '—';
        const msg = d.message ? String(d.message) : '';
        if (msg) {
            this.researchFeedHint.textContent = msg;
            return;
        }
        if (ws && we) {
            this.researchFeedHint.textContent = `统计窗口（UTC 日期）：${ws} ~ ${we} · 池中尚未展示：${rem} 条`;
        }
    }

    renderResearchFeedItems(items, append) {
        if (!this.researchFeedList) {
            return;
        }
        const html = (items || []).map((it) => {
            const title = this.escapeHtml(String(it.title || ''));
            let href = String(it.url || '').trim();
            try {
                href = encodeURI(href);
            } catch (e) {
                href = '#';
            }
            if (!href) {
                href = '#';
            }
            const src = this.escapeHtml(String(it.source || ''));
            const sum = this.escapeHtml(String(it.summary || ''));
            const pub = it.publishedAt ? this.formatResearchDate(String(it.publishedAt)) : '—';
            return `<li class="research-feed-item">
                <p class="research-feed-item-meta">${src} · ${this.escapeHtml(pub)}</p>
                <h4 class="research-feed-item-title"><a href="${href}" target="_blank" rel="noopener noreferrer">${title}</a></h4>
                <p class="research-feed-item-summary">${sum}</p>
            </li>`;
        }).join('');
        if (append) {
            this.researchFeedList.insertAdjacentHTML('beforeend', html);
        } else {
            this.researchFeedList.innerHTML = html || '<li class="server-monitor-err">暂无条目</li>';
        }
    }

    formatResearchDate(iso) {
        try {
            const ms = this.parseUtcInstantToEpochMs(iso);
            if (Number.isNaN(ms)) {
                return iso.slice(0, 10);
            }
            return new Date(ms).toLocaleString('zh-CN', { hour12: false, year: 'numeric', month: '2-digit', day: '2-digit' });
        } catch (e) {
            return iso.slice(0, 10);
        }
    }

    async fetchResearchFeedStart() {
        if (!this.researchFeedFetchBtn || !this.researchFeedList) {
            return;
        }
        this.researchFeedFetchBtn.disabled = true;
        if (this.researchFeedMoreBtn) {
            this.researchFeedMoreBtn.disabled = true;
        }
        this.researchFeedList.innerHTML = '<li class="research-feed-item">正在请求 arXiv / GitHub…</li>';
        try {
            const res = await fetch(`${this.apiBaseUrl}/research-feed/start`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
                body: '{}',
            });
            const json = await res.json();
            if (json.code !== 200 || !json.data) {
                this.researchFeedList.innerHTML = `<li class="server-monitor-err">${this.escapeHtml(json.message || '加载失败')}</li>`;
                return;
            }
            const d = json.data;
            this.researchFeedSessionId = d.sessionId || null;
            this.applyResearchFeedMeta(d);
            this.renderResearchFeedItems(d.items || [], false);
            if (this.researchFeedMoreBtn) {
                this.researchFeedMoreBtn.disabled = !d.hasMore;
            }
        } catch (e) {
            this.researchFeedList.innerHTML = `<li class="server-monitor-err">${this.escapeHtml(e.message || String(e))}</li>`;
        } finally {
            this.researchFeedFetchBtn.disabled = false;
        }
    }

    async fetchResearchFeedMore() {
        if (!this.researchFeedSessionId) {
            this.showNotification('请先点击「获取最近自动驾驶领域文章」', 'warning');
            return;
        }
        if (!this.researchFeedMoreBtn || !this.researchFeedList) {
            return;
        }
        this.researchFeedMoreBtn.disabled = true;
        this.researchFeedFetchBtn.disabled = true;
        try {
            const res = await fetch(`${this.apiBaseUrl}/research-feed/more`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
                body: JSON.stringify({ sessionId: this.researchFeedSessionId }),
            });
            const json = await res.json();
            if (json.code !== 200 || !json.data) {
                this.showNotification(json.message || '加载失败', 'warning');
                return;
            }
            const d = json.data;
            this.applyResearchFeedMeta(d);
            const batch = d.items || [];
            if (batch.length === 0) {
                this.showNotification(d.message || '没有更多未展示条目', 'info');
            } else {
                this.renderResearchFeedItems(batch, false);
            }
            if (this.researchFeedMoreBtn) {
                this.researchFeedMoreBtn.disabled = !d.hasMore;
            }
        } catch (e) {
            this.showNotification(e.message || String(e), 'warning');
        } finally {
            if (this.researchFeedMoreBtn) {
                this.researchFeedMoreBtn.disabled = false;
            }
            if (this.researchFeedFetchBtn) {
                this.researchFeedFetchBtn.disabled = false;
            }
        }
    }

    // 触发智能运维（点击智能运维按钮时直接调用）
    async triggerAIOps() {
        if (this.isStreaming) {
            this.showNotification('请等待当前操作完成', 'warning');
            return;
        }

        // 新建对话
        this.newChat();
        
        // 添加"分析中..."的消息（带旋转动画）
        const loadingMessage = this.addLoadingMessage('分析中...');
        this.currentAIOpsMessage = loadingMessage; // 保存消息引用用于后续更新
        
        // 设置发送状态
        this.isStreaming = true;
        this.updateUI();

        try {
            await this.sendAIOpsRequest(loadingMessage);
        } catch (error) {
            console.error('智能运维分析失败:', error);
            // 更新消息为错误信息
            if (loadingMessage) {
                const messageContent = loadingMessage.querySelector('.message-content');
                if (messageContent) {
                    messageContent.textContent = '抱歉，智能运维分析时出现错误：' + error.message;
                }
            }
        } finally {
            this.isStreaming = false;
            this.currentAIOpsMessage = null;
            this.updateUI();
        }
    }

    // 显示/隐藏加载遮罩层
    showLoadingOverlay(show) {
        if (this.loadingOverlay) {
            if (show) {
                this.loadingOverlay.style.display = 'flex';
                // 更新文字为智能运维
                const loadingText = this.loadingOverlay.querySelector('.loading-text');
                const loadingSubtext = this.loadingOverlay.querySelector('.loading-subtext');
                if (loadingText) loadingText.textContent = '智能运维分析中，请稍候...';
                if (loadingSubtext) loadingSubtext.textContent = '后端正在处理，请耐心等待';
                // 防止页面滚动
                document.body.style.overflow = 'hidden';
            } else {
                this.loadingOverlay.style.display = 'none';
                // 恢复页面滚动
                document.body.style.overflow = '';
            }
        }
    }

    // 显示/隐藏上传遮罩层
    showUploadOverlay(show, fileName = '') {
        if (this.loadingOverlay) {
            if (show) {
                this.loadingOverlay.style.display = 'flex';
                // 更新文字为上传中
                const loadingText = this.loadingOverlay.querySelector('.loading-text');
                const loadingSubtext = this.loadingOverlay.querySelector('.loading-subtext');
                if (loadingText) loadingText.textContent = '正在上传文件...';
                if (loadingSubtext) loadingSubtext.textContent = fileName ? `上传: ${fileName}` : '请稍候';
                // 防止页面滚动
                document.body.style.overflow = 'hidden';
            } else {
                this.loadingOverlay.style.display = 'none';
                // 恢复页面滚动
                document.body.style.overflow = '';
            }
        }
    }
}

// 添加CSS动画
const style = document.createElement('style');
style.textContent = `
    @keyframes slideIn {
        from {
            transform: translateX(100%);
            opacity: 0;
        }
        to {
            transform: translateX(0);
            opacity: 1;
        }
    }
    
    @keyframes slideOut {
        from {
            transform: translateX(0);
            opacity: 1;
        }
        to {
            transform: translateX(100%);
            opacity: 0;
        }
    }
`;
document.head.appendChild(style);

// 初始化应用
document.addEventListener('DOMContentLoaded', () => {
    new Auto206AgentApp();
});
