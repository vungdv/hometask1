/**
 * Polaris Web Chat AI Assistant Client
 * Features:
 * - Keycloak OAuth2 / OIDC PKCE Authorization Code Flow (RFC 7636, S256)
 * - SSE streaming reader (fetch + ReadableStream + TextDecoderStream)
 * - Interactive widgets (ProductCard, ProductListCard, OrderDraftCard, OrderConfirmedCard, ProblemCard)
 * - Mandatory Human-in-the-Loop confirmation gate with UUIDv4 Idempotency-Key
 * - 15-minute countdown draft TTL timer
 * - IME-safe Enter-to-submit
 */

(function () {
  'use strict';

  // --- Configuration ---
  const config = {
    // Keycloak OIDC Endpoints
    keycloakIssuer: 'https://id.polaris.local/realms/polaris',
    authEndpoint: 'https://id.polaris.local/realms/polaris/protocol/openid-connect/auth',
    tokenEndpoint: 'https://id.polaris.local/realms/polaris/protocol/openid-connect/token',
    clientId: 'polaris-app',
    redirectUri: window.location.origin + '/chat',
    scope: 'openid profile email',

    // Polaris Backend Assistant APIs
    sessionApi: '/api/v1/assistant/sessions',
    messagesApi: (sessionId) => `/api/v1/assistant/sessions/${sessionId}/messages`,
    confirmDraftApi: (sessionId, draftId) => `/api/v1/assistant/sessions/${sessionId}/drafts/${draftId}/confirm`,
    cancelDraftApi: (sessionId, draftId) => `/api/v1/assistant/sessions/${sessionId}/drafts/${draftId}/cancel`
  };

  // --- State ---
  const state = {
    accessToken: sessionStorage.getItem('polaris_access_token') || null,
    refreshToken: sessionStorage.getItem('polaris_refresh_token') || null,
    user: null,
    sessionId: sessionStorage.getItem('polaris_session_id') || null,
    isStreaming: false,
    draftTimers: new Map()
  };

  // --- DOM Elements ---
  const elements = {
    chatLog: document.getElementById('chat-log'),
    chatForm: document.getElementById('chat-form'),
    chatInput: document.getElementById('chat-input'),
    sendButton: document.getElementById('send-button'),
    sessionIdDisplay: document.getElementById('session-id-display'),
    userMeta: document.getElementById('user-meta'),
    userBadge: document.getElementById('user-badge'),
    roleBadge: document.getElementById('role-badge'),
    loginButton: document.getElementById('login-button'),
    logoutButton: document.getElementById('logout-button'),
    authBanner: document.getElementById('auth-banner'),
    bannerLoginBtn: document.getElementById('banner-login-btn')
  };

  // --- PKCE Helpers (RFC 7636) ---
  function generateRandomString(length = 64) {
    const charset = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~';
    const randomValues = new Uint8Array(length);
    window.crypto.getRandomValues(randomValues);
    return Array.from(randomValues).map(val => charset[val % charset.length]).join('');
  }

  async function sha256(plain) {
    const encoder = new TextEncoder();
    const data = encoder.encode(plain);
    return window.crypto.subtle.digest('SHA-256', data);
  }

  function base64UrlEncode(buffer) {
    const bytes = new Uint8Array(buffer);
    let binary = '';
    for (let i = 0; i < bytes.byteLength; i++) {
      binary += String.fromCharCode(bytes[i]);
    }
    return btoa(binary)
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/, '');
  }

  // --- Authentication Flow ---
  async function initiateLogin() {
    const codeVerifier = generateRandomString(64);
    sessionStorage.setItem('pkce_code_verifier', codeVerifier);

    const hashed = await sha256(codeVerifier);
    const codeChallenge = base64UrlEncode(hashed);

    const params = new URLSearchParams({
      client_id: config.clientId,
      response_type: 'code',
      scope: config.scope,
      redirect_uri: config.redirectUri,
      code_challenge: codeChallenge,
      code_challenge_method: 'S256'
    });

    window.location.href = `${config.authEndpoint}?${params.toString()}`;
  }

  async function handleOAuthCallback() {
    const urlParams = new URLSearchParams(window.location.search);
    const code = urlParams.get('code');

    if (!code) {
      return false;
    }

    const codeVerifier = sessionStorage.getItem('pkce_code_verifier');
    if (!codeVerifier) {
      console.warn('No PKCE code verifier found in sessionStorage.');
      return false;
    }

    try {
      const body = new URLSearchParams({
        grant_type: 'authorization_code',
        client_id: config.clientId,
        code: code,
        redirect_uri: config.redirectUri,
        code_verifier: codeVerifier
      });

      const response = await fetch(config.tokenEndpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: body.toString()
      });

      if (!response.ok) {
        throw new Error(`Token exchange failed: ${response.status}`);
      }

      const tokenData = await response.json();
      setTokens(tokenData.access_token, tokenData.refresh_token);
      sessionStorage.removeItem('pkce_code_verifier');

      // Clean URL
      window.history.replaceState({}, document.title, window.location.pathname);
      return true;
    } catch (err) {
      console.error('OAuth Callback Error:', err);
      return false;
    }
  }

  function setTokens(accessToken, refreshToken) {
    state.accessToken = accessToken;
    state.refreshToken = refreshToken;
    if (accessToken) {
      sessionStorage.setItem('polaris_access_token', accessToken);
      decodeAndSetUser(accessToken);
    } else {
      sessionStorage.removeItem('polaris_access_token');
      state.user = null;
    }
    if (refreshToken) {
      sessionStorage.setItem('polaris_refresh_token', refreshToken);
    } else {
      sessionStorage.removeItem('polaris_refresh_token');
    }
    updateAuthUI();
  }

  function decodeAndSetUser(token) {
    try {
      const payloadBase64 = token.split('.')[1];
      const decodedJson = atob(payloadBase64.replace(/-/g, '+').replace(/_/g, '/'));
      const payload = JSON.parse(decodedJson);

      const username = payload.preferred_username || payload.sub || 'User';
      const roles = (payload.realm_access && payload.realm_access.roles) || [];
      const isStaff = roles.includes('ROLE_STAFF') || roles.includes('ROLE_ADMIN') || roles.includes('admin');

      state.user = {
        username: username,
        roles: roles,
        isStaff: isStaff,
        displayRole: isStaff ? 'ROLE_STAFF' : 'ROLE_USER'
      };
    } catch (e) {
      console.error('Failed to parse JWT payload', e);
      state.user = { username: 'User', displayRole: 'ROLE_USER', isStaff: false };
    }
  }

  function logout() {
    setTokens(null, null);
    state.sessionId = null;
    sessionStorage.removeItem('polaris_session_id');
    updateAuthUI();
    elements.sessionIdDisplay.textContent = 'Not connected';
  }

  function updateAuthUI() {
    const isAuthenticated = !!state.accessToken;
    if (isAuthenticated && state.user) {
      elements.userMeta.hidden = false;
      elements.loginButton.hidden = true;
      elements.authBanner.hidden = true;
      elements.userBadge.textContent = state.user.username;
      elements.roleBadge.textContent = state.user.displayRole;
      if (state.user.isStaff) {
        elements.roleBadge.style.background = 'rgba(245, 158, 11, 0.15)';
        elements.roleBadge.style.color = '#f59e0b';
      }
    } else {
      elements.userMeta.hidden = true;
      elements.loginButton.hidden = false;
      elements.authBanner.hidden = false;
    }
  }

  // --- Session Management ---
  async function ensureActiveSession() {
    if (state.sessionId) {
      try {
        const res = await fetch(`${config.sessionApi}/${state.sessionId}`, {
          headers: { 'Authorization': `Bearer ${state.accessToken}` }
        });
        if (res.ok) {
          const detail = await res.json();
          if (detail.status === 'ACTIVE') {
            elements.sessionIdDisplay.textContent = state.sessionId;
            return state.sessionId;
          }
        }
      } catch (e) {
        console.warn('Error fetching existing session, creating a new one.', e);
      }
    }

    // Create new session
    try {
      const res = await fetch(config.sessionApi, {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${state.accessToken}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({})
      });

      if (!res.ok) {
        throw new Error(`Failed to create session: ${res.status}`);
      }

      const sessionData = await res.json();
      state.sessionId = sessionData.id;
      sessionStorage.setItem('polaris_session_id', state.sessionId);
      elements.sessionIdDisplay.textContent = state.sessionId;
      return state.sessionId;
    } catch (e) {
      console.error('Session initialization error', e);
      appendSystemMessage('Unable to establish an assistant session with the server.');
      return null;
    }
  }

  // --- Message UI Rendering ---
  function appendUserMessage(text) {
    const bubble = document.createElement('div');
    bubble.className = 'message-bubble user-turn';

    const header = document.createElement('div');
    header.className = 'bubble-header';
    header.innerHTML = `<span class="author-name">You</span><time class="message-time">${new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</time>`;

    const content = document.createElement('div');
    content.className = 'bubble-content';
    content.textContent = text;

    bubble.appendChild(header);
    bubble.appendChild(content);
    elements.chatLog.appendChild(bubble);
    scrollToBottom();
  }

  function createAssistantTurn() {
    const bubble = document.createElement('div');
    bubble.className = 'message-bubble assistant-turn';

    const header = document.createElement('div');
    header.className = 'bubble-header';
    header.innerHTML = `<span class="author-name">Polaris Assistant</span><time class="message-time">${new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</time>`;

    const thoughtContainer = document.createElement('details');
    thoughtContainer.className = 'thought-block';
    thoughtContainer.hidden = true;
    thoughtContainer.innerHTML = `<summary class="thought-summary">🧠 Reasoning</summary><div class="thought-content"></div>`;

    const content = document.createElement('div');
    content.className = 'bubble-content';

    const typing = document.createElement('div');
    typing.className = 'typing-indicator';
    typing.innerHTML = `<div class="typing-dot"></div><div class="typing-dot"></div><div class="typing-dot"></div>`;

    bubble.appendChild(header);
    bubble.appendChild(thoughtContainer);
    bubble.appendChild(content);
    bubble.appendChild(typing);

    elements.chatLog.appendChild(bubble);
    scrollToBottom();

    return {
      bubble,
      thoughtContainer,
      thoughtContent: thoughtContainer.querySelector('.thought-content'),
      content,
      typing
    };
  }

  function appendSystemMessage(text) {
    const bubble = document.createElement('div');
    bubble.className = 'message-bubble system-welcome';
    bubble.innerHTML = `<div class="bubble-content"><p>${escapeHtml(text)}</p></div>`;
    elements.chatLog.appendChild(bubble);
    scrollToBottom();
  }

  function scrollToBottom() {
    elements.chatLog.scrollTop = elements.chatLog.scrollHeight;
  }

  // --- Interactive Card Renderers ---

  function renderProductCard(product, container) {
    const card = document.createElement('div');
    card.className = 'card-widget product-card';

    const stockQty = product.stockQuantity != null ? product.stockQuantity : product.stockQty || 0;
    const isStocked = stockQty > 0;

    card.innerHTML = `
      <div class="card-header-row">
        <span class="product-sku">${escapeHtml(product.sku)}</span>
        <span class="product-price">$${Number(product.price || 0).toFixed(2)}</span>
      </div>
      <div class="product-name">${escapeHtml(product.name)}</div>
      <div class="card-header-row">
        <span class="product-stock-badge ${isStocked ? 'in-stock' : 'out-of-stock'}">
          ${isStocked ? `In Stock (${stockQty} units)` : 'Out of Stock'}
        </span>
        ${isStocked ? `<button class="btn btn-primary btn-sm btn-add-draft" data-sku="${escapeHtml(product.sku)}">Add to Draft</button>` : ''}
      </div>
    `;

    card.querySelectorAll('.btn-add-draft').forEach(btn => {
      btn.addEventListener('click', () => {
        const sku = btn.getAttribute('data-sku');
        submitUserPrompt(`order 1 ${sku}`);
      });
    });

    container.appendChild(card);
  }

  function renderProductListCard(products, container) {
    if (!Array.isArray(products) || products.length === 0) return;

    const card = document.createElement('div');
    card.className = 'card-widget product-list-card';

    let tableHtml = `
      <table>
        <thead>
          <tr>
            <th>SKU</th>
            <th>Name</th>
            <th>Price</th>
            <th>Stock</th>
            <th>Action</th>
          </tr>
        </thead>
        <tbody>
    `;

    products.forEach(p => {
      const stockQty = p.stockQuantity != null ? p.stockQuantity : p.stockQty || 0;
      tableHtml += `
        <tr>
          <td><span class="product-sku">${escapeHtml(p.sku)}</span></td>
          <td>${escapeHtml(p.name)}</td>
          <td>$${Number(p.price || 0).toFixed(2)}</td>
          <td>${stockQty}</td>
          <td>
            ${stockQty > 0 ? `<button class="btn btn-primary btn-sm btn-add-draft" data-sku="${escapeHtml(p.sku)}">Add</button>` : '<span class="out-of-stock">0</span>'}
          </td>
        </tr>
      `;
    });

    tableHtml += `</tbody></table>`;
    card.innerHTML = tableHtml;

    card.querySelectorAll('.btn-add-draft').forEach(btn => {
      btn.addEventListener('click', () => {
        const sku = btn.getAttribute('data-sku');
        submitUserPrompt(`order 1 ${sku}`);
      });
    });

    container.appendChild(card);
  }

  function renderOrderDraftCard(draft, container) {
    const card = document.createElement('div');
    card.className = 'card-widget draft-card';
    card.id = `draft-card-${draft.id}`;

    const items = draft.items || [];
    let itemsRows = '';
    items.forEach(item => {
      itemsRows += `
        <tr>
          <td>${escapeHtml(item.productName || item.sku)}</td>
          <td class="num-col">${item.quantity}</td>
          <td class="num-col">$${Number(item.unitPrice || 0).toFixed(2)}</td>
          <td class="num-col">$${Number(item.subtotal || 0).toFixed(2)}</td>
        </tr>
      `;
    });

    card.innerHTML = `
      <div class="draft-header">
        <div class="draft-title">
          <span>🛒 Staged Order Draft</span>
        </div>
        <div class="draft-timer" id="timer-${draft.id}">15:00</div>
      </div>
      <table class="draft-items-table">
        <thead>
          <tr>
            <th>Item</th>
            <th class="num-col">Qty</th>
            <th class="num-col">Unit</th>
            <th class="num-col">Subtotal</th>
          </tr>
        </thead>
        <tbody>
          ${itemsRows}
          <tr class="draft-total-row">
            <td colspan="3">Grand Total</td>
            <td class="num-col">$${Number(draft.totalAmount || 0).toFixed(2)}</td>
          </tr>
        </tbody>
      </table>
      <div class="draft-actions-row">
        <button class="btn btn-secondary btn-sm btn-cancel-draft" data-draft-id="${escapeHtml(draft.id)}">Cancel Draft</button>
        <button class="btn btn-confirm-order" data-action="confirm-order" data-draft-id="${escapeHtml(draft.id)}">
          ✓ Submit Order
        </button>
      </div>
    `;

    // Start Countdown Timer
    startDraftTimer(draft.id, draft.expiresAt, card);

    // Human-in-the-Loop Submit Order Gate
    const confirmBtn = card.querySelector('[data-action="confirm-order"]');
    if (confirmBtn) {
      confirmBtn.addEventListener('click', async () => {
        confirmBtn.disabled = true;
        confirmBtn.textContent = 'Submitting...';
        await executeConfirmDraft(draft.id, card);
      });
    }

    const cancelBtn = card.querySelector('.btn-cancel-draft');
    if (cancelBtn) {
      cancelBtn.addEventListener('click', async () => {
        cancelBtn.disabled = true;
        await executeCancelDraft(draft.id, card);
      });
    }

    container.appendChild(card);
  }

  function startDraftTimer(draftId, expiresAtStr, cardElement) {
    if (state.draftTimers.has(draftId)) {
      clearInterval(state.draftTimers.get(draftId));
    }

    const timerEl = cardElement.querySelector(`#timer-${draftId}`);
    if (!timerEl) return;

    const expiresAt = expiresAtStr ? new Date(expiresAtStr).getTime() : Date.now() + 15 * 60 * 1000;

    const interval = setInterval(() => {
      const now = Date.now();
      const diffMs = expiresAt - now;

      if (diffMs <= 0) {
        clearInterval(interval);
        state.draftTimers.delete(draftId);
        timerEl.textContent = 'EXPIRED';
        timerEl.style.color = 'var(--color-danger)';
        const submitBtn = cardElement.querySelector('[data-action="confirm-order"]');
        if (submitBtn) {
          submitBtn.disabled = true;
          submitBtn.textContent = 'Draft Expired';
        }
        return;
      }

      const totalSec = Math.floor(diffMs / 1000);
      const min = Math.floor(totalSec / 60);
      const sec = totalSec % 60;
      timerEl.textContent = `${min.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}`;
    }, 1000);

    state.draftTimers.set(draftId, interval);
  }

  async function executeConfirmDraft(draftId, draftCard) {
    const idempotencyKey = window.crypto.randomUUID();
    try {
      const res = await fetch(config.confirmDraftApi(state.sessionId, draftId), {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${state.accessToken}`,
          'Idempotency-Key': idempotencyKey
        }
      });

      if (!res.ok) {
        const errorJson = await res.json().catch(() => ({}));
        throw new Error(errorJson.detail || `Order confirmation failed (Status ${res.status})`);
      }

      const order = await res.json();

      // Clear timer
      if (state.draftTimers.has(draftId)) {
        clearInterval(state.draftTimers.get(draftId));
        state.draftTimers.delete(draftId);
      }

      // Transition to Confirmed Order Card
      renderConfirmedCard(order, draftCard);
    } catch (err) {
      alert(`Error submitting order: ${err.message}`);
      const btn = draftCard.querySelector('[data-action="confirm-order"]');
      if (btn) {
        btn.disabled = false;
        btn.textContent = '✓ Submit Order';
      }
    }
  }

  async function executeCancelDraft(draftId, draftCard) {
    try {
      const res = await fetch(config.cancelDraftApi(state.sessionId, draftId), {
        method: 'POST',
        headers: { 'Authorization': `Bearer ${state.accessToken}` }
      });

      if (state.draftTimers.has(draftId)) {
        clearInterval(state.draftTimers.get(draftId));
        state.draftTimers.delete(draftId);
      }

      draftCard.style.opacity = '0.5';
      draftCard.innerHTML = `<div class="bubble-content"><p>Draft ${escapeHtml(draftId)} cancelled.</p></div>`;
    } catch (e) {
      console.error('Cancel draft error', e);
    }
  }

  function renderConfirmedCard(order, targetElement) {
    const card = document.createElement('div');
    card.className = 'card-widget confirmed-card';
    card.innerHTML = `
      <div class="confirmed-title">🎉 Order Confirmed: ${escapeHtml(order.orderNumber)}</div>
      <p style="font-size: 0.9rem;">Status: <strong>${escapeHtml(order.status || 'PLACED')}</strong> — Total: <strong>$${Number(order.totalAmount || 0).toFixed(2)}</strong></p>
      <p class="bubble-hint">Inventory stock deducted. Your order is now queued for parcel preparation.</p>
    `;

    if (targetElement) {
      targetElement.replaceWith(card);
    } else {
      elements.chatLog.appendChild(card);
    }
    scrollToBottom();
  }

  function renderProblemCard(problem, container) {
    const card = document.createElement('div');
    card.className = 'card-widget problem-card';

    const actions = Array.isArray(problem.actions) ? problem.actions : [];
    let actionsHtml = '';
    actions.forEach(a => {
      actionsHtml += `
        <button
          class="btn btn-remedy"
          type="button"
          data-action="${escapeHtml(a.action || '')}"
          data-sku="${escapeHtml(a.sku || '')}"
          data-qty="${escapeHtml(String(a.quantity || ''))}"
          data-query="${escapeHtml(a.query || '')}"
        >
          ${escapeHtml(a.label || 'Fix')}
        </button>
      `;
    });

    card.innerHTML = `
      <div class="problem-title">
        <span>⚠️ ${escapeHtml(problem.title || 'Diagnostic Problem')} (${problem.status || 400})</span>
      </div>
      <div style="font-size: 0.9rem;">${escapeHtml(problem.detail || '')}</div>
      ${problem.remedy ? `<div class="problem-remedy">💡 ${escapeHtml(problem.remedy)}</div>` : ''}
      ${actionsHtml ? `<div class="problem-actions-row">${actionsHtml}</div>` : ''}
    `;

    // Bind remedy action triggers
    card.querySelectorAll('.btn-remedy').forEach(btn => {
      btn.addEventListener('click', () => {
        const act = btn.getAttribute('data-action');
        const sku = btn.getAttribute('data-sku');
        const qty = btn.getAttribute('data-qty');
        const query = btn.getAttribute('data-query');

        if (act === 'adjust_quantity' && sku && qty) {
          submitUserPrompt(`order ${qty} ${sku}`);
        } else if (act === 'search_alternatives' && query) {
          submitUserPrompt(`find ${query}`);
        } else if (act === 'view_returns') {
          appendSystemMessage('Return Policy: Non-cancellable orders (e.g. DELIVERED) can be returned within 30 days of delivery via support.');
        } else if (act === 'refresh_draft') {
          submitUserPrompt('stage order draft');
        } else if (act === 'view_my_orders') {
          submitUserPrompt('status of my orders');
        }
      });
    });

    container.appendChild(card);
    scrollToBottom();
  }

  // --- SSE Streaming Reader ---
  async function submitUserPrompt(promptText) {
    if (!promptText || !promptText.trim()) return;
    if (state.isStreaming) return;

    if (!state.accessToken) {
      initiateLogin();
      return;
    }

    const sessionId = await ensureActiveSession();
    if (!sessionId) return;

    appendUserMessage(promptText);
    elements.chatInput.value = '';
    adjustTextareaHeight();

    state.isStreaming = true;
    elements.sendButton.disabled = true;

    const turn = createAssistantTurn();

    try {
      const response = await fetch(config.messagesApi(sessionId), {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${state.accessToken}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({ content: promptText })
      });

      if (!response.ok) {
        throw new Error(`SSE streaming failed with status ${response.status}`);
      }

      const reader = response.body
        .pipeThrough(new TextDecoderStream())
        .getReader();

      let buffer = '';

      while (true) {
        const { value, done } = await reader.read();
        if (done) break;

        buffer += value;
        const lines = buffer.split('\n');
        buffer = lines.pop(); // keep last incomplete line

        let currentEvent = null;

        for (const line of lines) {
          const trimmed = line.trim();
          if (trimmed.startsWith(':')) {
            // Keep-alive heartbeat comment
            continue;
          }
          if (trimmed.startsWith('event:')) {
            currentEvent = trimmed.substring(6).trim();
          } else if (trimmed.startsWith('data:')) {
            const dataStr = trimmed.substring(5).trim();
            handleSseEvent(currentEvent, dataStr, turn);
            currentEvent = null;
          }
        }
      }
    } catch (err) {
      console.error('SSE Stream Error:', err);
      turn.content.textContent += `\n[Error: ${err.message}]`;
    } finally {
      if (turn.typing) turn.typing.remove();
      state.isStreaming = false;
      elements.sendButton.disabled = false;
      elements.chatInput.focus();
    }
  }

  function handleSseEvent(eventType, dataStr, turn) {
    let parsedData = dataStr;
    try {
      parsedData = JSON.parse(dataStr);
    } catch (e) {
      // plain text data
    }

    switch (eventType) {
      case 'thought': {
        turn.thoughtContainer.hidden = false;
        const thoughtText = typeof parsedData === 'object' && parsedData.thought ? parsedData.thought : dataStr;
        turn.thoughtContent.textContent += `${thoughtText}\n`;
        break;
      }
      case 'token': {
        const delta = typeof parsedData === 'object' && parsedData.delta ? parsedData.delta : dataStr;
        turn.content.textContent += delta;
        scrollToBottom();
        break;
      }
      case 'widget': {
        const type = parsedData.type;
        const payload = parsedData.payload;
        if (type === 'PRODUCT_CARD') {
          renderProductCard(payload, turn.bubble);
        } else if (type === 'PRODUCT_LIST_CARD') {
          renderProductListCard(payload, turn.bubble);
        } else if (type === 'PROBLEM_CARD') {
          renderProblemCard(payload, turn.bubble);
        } else if (type === 'ORDER_STATUS_CARD') {
          renderConfirmedCard(payload, null);
        }
        break;
      }
      case 'draft': {
        renderOrderDraftCard(parsedData, turn.bubble);
        break;
      }
      case 'error': {
        renderProblemCard(parsedData, turn.bubble);
        break;
      }
      case 'done': {
        if (turn.typing) turn.typing.remove();
        break;
      }
      default: {
        // Unnamed or raw data frame
        if (typeof parsedData === 'object' && parsedData.delta) {
          turn.content.textContent += parsedData.delta;
          scrollToBottom();
        }
      }
    }
  }

  // --- Helpers ---
  function escapeHtml(str) {
    if (str == null) return '';
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }

  function adjustTextareaHeight() {
    elements.chatInput.style.height = 'auto';
    elements.chatInput.style.height = `${Math.min(elements.chatInput.scrollHeight, 140)}px`;
  }

  // --- Event Listeners Initialization ---
  function setupEventListeners() {
    // IME-Safe Enter-to-Submit (modern-web-guidance)
    elements.chatInput.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();
        // Block submission if composing natively or if keyCode is 229
        if (event.isComposing || event.keyCode === 229) {
          return;
        }
        elements.chatForm.requestSubmit();
      }
    });

    elements.chatInput.addEventListener('input', adjustTextareaHeight);

    elements.chatForm.addEventListener('submit', (e) => {
      e.preventDefault();
      const prompt = elements.chatInput.value.trim();
      submitUserPrompt(prompt);
    });

    // Quick prompt chips
    document.querySelectorAll('.chip-btn').forEach(btn => {
      btn.addEventListener('click', () => {
        const prompt = btn.getAttribute('data-prompt');
        submitUserPrompt(prompt);
      });
    });

    // Auth buttons
    elements.loginButton.addEventListener('click', initiateLogin);
    elements.bannerLoginBtn.addEventListener('click', initiateLogin);
    elements.logoutButton.addEventListener('click', logout);
  }

  // --- Bootstrap ---
  async function init() {
    setupEventListeners();

    // Check for PKCE OAuth callback in query parameters
    const fromCallback = await handleOAuthCallback();

    if (state.accessToken) {
      decodeAndSetUser(state.accessToken);
      updateAuthUI();
      await ensureActiveSession();
    } else {
      updateAuthUI();
    }
  }

  document.addEventListener('DOMContentLoaded', init);
})();
