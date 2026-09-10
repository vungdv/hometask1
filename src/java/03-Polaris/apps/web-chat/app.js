(function () {
  'use strict';

  const STORAGE_KEY = 'polaris_chat_session_id';
  const API_ENDPOINT = '/api/v1/assistant/chat';

  const messagesContainer = document.getElementById('chat-messages');
  const chatForm = document.getElementById('chat-form');
  const chatInput = document.getElementById('chat-input');
  const sendBtn = document.getElementById('send-btn');
  const newChatBtn = document.getElementById('new-chat-btn');

  let sessionId = sessionStorage.getItem(STORAGE_KEY) || null;
  let isSending = false;

  function scrollToBottom() {
    messagesContainer.scrollTop = messagesContainer.scrollHeight;
  }

  function appendMessage(role, text) {
    const isUser = role === 'user';
    const msgEl = document.createElement('div');
    msgEl.className = `message ${isUser ? 'user-msg' : 'assistant-msg'}`;

    if (!isUser) {
      const avatar = document.createElement('div');
      avatar.className = 'avatar';
      avatar.setAttribute('aria-hidden', 'true');
      avatar.textContent = '★';
      msgEl.appendChild(avatar);
    }

    const bubble = document.createElement('div');
    bubble.className = 'bubble';

    const p = document.createElement('p');
    p.textContent = text;
    p.style.whiteSpace = 'pre-wrap';
    bubble.appendChild(p);

    msgEl.appendChild(bubble);
    messagesContainer.appendChild(msgEl);
    scrollToBottom();
    return msgEl;
  }

  function showTypingIndicator() {
    const indicator = document.createElement('div');
    indicator.id = 'typing-indicator';
    indicator.className = 'message assistant-msg';

    const avatar = document.createElement('div');
    avatar.className = 'avatar';
    avatar.setAttribute('aria-hidden', 'true');
    avatar.textContent = '★';
    indicator.appendChild(avatar);

    const bubble = document.createElement('div');
    bubble.className = 'bubble typing-bubble';
    bubble.innerHTML = '<span class="dot"></span><span class="dot"></span><span class="dot"></span>';
    indicator.appendChild(bubble);

    messagesContainer.appendChild(indicator);
    scrollToBottom();
  }

  function removeTypingIndicator() {
    const indicator = document.getElementById('typing-indicator');
    if (indicator) {
      indicator.remove();
    }
  }

  async function loadHistory() {
    if (!sessionId) return;
    try {
      const res = await fetch(`${API_ENDPOINT}/history/${sessionId}`);
      if (!res.ok) {
        sessionStorage.removeItem(STORAGE_KEY);
        sessionId = null;
        return;
      }
      const data = await res.json();
      if (data.messages && data.messages.length > 0) {
        messagesContainer.innerHTML = '';
        data.messages.forEach(m => {
          appendMessage(m.role.toLowerCase(), m.content);
        });
      }
    } catch (e) {
      console.warn('Could not restore chat history', e);
    }
  }

  async function sendMessage(text) {
    if (!text || isSending) return;

    isSending = true;
    chatInput.value = '';
    chatInput.disabled = true;
    sendBtn.disabled = true;

    appendMessage('user', text);
    showTypingIndicator();

    try {
      const payload = { message: text };
      if (sessionId) {
        payload.sessionId = sessionId;
      }

      const res = await fetch(API_ENDPOINT, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });

      removeTypingIndicator();

      if (!res.ok) {
        const errData = await res.json().catch(() => ({}));
        const errDetail = errData.detail || errData.message || `Server responded with status ${res.status}`;
        appendMessage('assistant', `⚠️ ${errDetail}`);
        return;
      }

      const data = await res.json();
      if (data.sessionId) {
        sessionId = data.sessionId;
        sessionStorage.setItem(STORAGE_KEY, sessionId);
      }

      appendMessage('assistant', data.reply || '(Empty response from AI Model)');
    } catch (err) {
      removeTypingIndicator();
      console.error('Network or chat error:', err);
      appendMessage('assistant', '⚠️ Unable to reach Polaris Assistant. Please check server connection.');
    } finally {
      isSending = false;
      chatInput.disabled = false;
      sendBtn.disabled = false;
      chatInput.focus();
    }
  }

  chatForm.addEventListener('submit', (e) => {
    e.preventDefault();
    const text = chatInput.value.trim();
    if (text) {
      sendMessage(text);
    }
  });

  newChatBtn.addEventListener('click', () => {
    sessionStorage.removeItem(STORAGE_KEY);
    sessionId = null;
    messagesContainer.innerHTML = `
      <div class="message assistant-msg welcome-msg">
        <div class="avatar" aria-hidden="true">★</div>
        <div class="bubble">
          <p>Hello! I am your <strong>Polaris Assistant</strong>. How can I help you today?</p>
        </div>
      </div>
    `;
    chatInput.value = '';
    chatInput.focus();
  });

  // Initialize
  loadHistory();
  chatInput.focus();
})();
