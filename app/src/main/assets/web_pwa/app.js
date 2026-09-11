// GCTU Campus Mesh PWA Client

// App State
let myPeerID = '';
let myUsername = '';
let myRole = '';
let currentChannel = '#general';
let socket = null;
let map = null;
let peerMarkers = {};
let offlineTileLayer = null;

// GCTU Campus Coordinates
const CAMPUSES = {
    main: { name: "GCTU Main Campus (Tesano)", lat: 5.5961352, lon: -0.2234766, radius: 300 },
    abeka: { name: "Abeka Campus (SITB)", lat: 5.5995349, lon: -0.2388291, radius: 200 }
};

// Ghost Mode: when true, this client never sends its own marker to the map layer and never
// requests peer position broadcast. Persisted client-side; the native gateway is told about it
// via the WebSocket handshake so it can (in the future, once real peer-location broadcast
// exists) suppress this device from other peers' maps too.
let isGhostMode = localStorage.getItem('campusmesh_ghost_mode') === 'true';

// DOM Elements
const onboardingOverlay = document.getElementById('onboarding-overlay');
const joinBtn = document.getElementById('join-btn');
const usernameInput = document.getElementById('username-input');
const roleSelect = document.getElementById('role-select');
const keygenStatus = document.getElementById('keygen-status');
const connectionBadge = document.getElementById('connection-badge');
const userNameDisplay = document.getElementById('user-name');
const userRoleDisplay = document.getElementById('user-role');
const myPeerIdDisplay = document.getElementById('my-peer-id');
const messagesList = document.getElementById('messages-list');
const messageInput = document.getElementById('message-input');
const sendBtn = document.getElementById('send-btn');
const alertBanner = document.getElementById('alert-banner');
const alertMessage = document.getElementById('alert-message');
const alertClose = document.getElementById('alert-close');
const mapPeerCount = document.getElementById('map-peer-count');

// Tab Selection
const showChatTab = document.getElementById('show-chat-tab');
const showMapTab = document.getElementById('show-map-tab');
const chatTabContent = document.getElementById('chat-tab-content');
const mapTabContent = document.getElementById('map-tab-content');

// --- Initialization ---
window.addEventListener('DOMContentLoaded', () => {
    // Check if user is already onboarded
    const savedName = localStorage.getItem('mesh_username');
    const savedRole = localStorage.getItem('mesh_role');
    const savedPeerID = localStorage.getItem('mesh_peer_id');

    if (savedName && savedRole && savedPeerID) {
        myUsername = savedName;
        myRole = savedRole;
        myPeerID = savedPeerID;
        
        onboardingOverlay.classList.add('overlay-hidden');
        initializeUI();
        connectWebSocket();
    } else {
        // Show onboarding overlay
        onboardingOverlay.classList.remove('overlay-hidden');
    }
    
    setupTabSwitching();
});

// Join Button Listener
joinBtn.addEventListener('click', async () => {
    const username = usernameInput.value.trim();
    const role = roleSelect.value;

    if (!username) {
        alert('Please enter a nickname.');
        return;
    }

    joinBtn.disabled = true;
    keygenStatus.classList.remove('status-msg-hidden');

    myUsername = username;
    myRole = role;

    // Generate decentralized identity
    myPeerID = await generateIdentityID();

    // Save to storage
    localStorage.setItem('mesh_username', myUsername);
    localStorage.setItem('mesh_role', myRole);
    localStorage.setItem('mesh_peer_id', myPeerID);

    keygenStatus.classList.add('status-msg-hidden');
    onboardingOverlay.classList.add('overlay-hidden');

    initializeUI();
    connectWebSocket();
});

// Generate Web Crypto Key ID or Fallback
async function generateIdentityID() {
    try {
        if (window.crypto && window.crypto.subtle) {
            // Generate a random seed key pair (Web Crypto Subtle)
            const keys = await window.crypto.subtle.generateKey(
                { name: "ECDSA", namedCurve: "P-256" },
                true,
                ["sign", "verify"]
            );
            const pubKey = await window.crypto.subtle.exportKey("raw", keys.publicKey);
            const hashBuffer = await window.crypto.subtle.digest("SHA-256", pubKey);
            const hashArray = Array.from(new Uint8Array(hashBuffer));
            const hex = hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
            return 'web-' + hex.substring(0, 12);
        }
    } catch (e) {
        console.warn("Web Crypto not supported, falling back to random generation:", e);
    }
    
    // Fallback: Secure random 12-char hex
    const arr = new Uint8Array(6);
    window.crypto.getRandomValues(arr);
    return 'web-' + Array.from(arr).map(b => b.toString(16).padStart(2, '0')).join('');
}

function initializeUI() {
    userNameDisplay.textContent = myUsername;
    userRoleDisplay.textContent = myRole;
    myPeerIdDisplay.textContent = myPeerID;
    
    // Set up Avatar emoji based on role
    const avatar = document.getElementById('user-avatar');
    avatar.textContent = myRole === 'Lecturer' ? '👨‍🏫' : '🎓';

    // Clear sample messages
    messagesList.innerHTML = '';
    appendSystemMessage("Secure offline connection established with Android Gateway.");

    // Initial setup of Leaflet Map
    setupMap();

    // Ghost Mode toggle
    const ghostBtn = document.getElementById('ghost-mode-toggle');
    if (ghostBtn) {
        updateGhostModeButton();
        ghostBtn.addEventListener('click', () => setGhostMode(!isGhostMode));
    }
}

function updateGhostModeButton() {
    const btn = document.getElementById('ghost-mode-toggle');
    const label = document.getElementById('ghost-mode-label');
    if (!btn || !label) return;
    btn.classList.toggle('ghost-active', isGhostMode);
    label.textContent = isGhostMode ? 'Ghost Mode: On' : 'Go Ghost';
}

// --- Pairing token ---
// The gateway is reachable by anything on the same Wi-Fi, so the bridge requires the token the
// host handed out with the invite URL (?t=...). Cached so reloads and the 3s reconnect below keep
// working after the query string is gone from the address bar.
function pairingToken() {
    const fromUrl = new URLSearchParams(window.location.search).get('t');
    if (fromUrl) {
        try { localStorage.setItem('cm_pairing_token', fromUrl); } catch (e) { /* private mode */ }
        return fromUrl;
    }
    try {
        return localStorage.getItem('cm_pairing_token') || '';
    } catch (e) {
        return '';
    }
}

// --- WebSocket Connection ---
function connectWebSocket() {
    const host = window.location.host || 'localhost:8080';
    const token = pairingToken();

    if (!token) {
        console.warn("No pairing token - open the invite link the host gave you.");
        connectionBadge.textContent = "Not paired";
        connectionBadge.className = "badge badge-disconnected";
        return;
    }

    const wsUrl = `ws://${host}/chat-ws?t=${encodeURIComponent(token)}`;

    console.log("Connecting to Ktor WebSocket...");
    socket = new WebSocket(wsUrl);

    socket.onopen = () => {
        console.log("WebSocket connected.");
        connectionBadge.textContent = "Online";
        connectionBadge.className = "badge badge-connected";

        // Send handshake
        const handshake = {
            type: "handshake",
            peerID: myPeerID,
            username: myUsername,
            role: myRole,
            ghostMode: isGhostMode
        };
        socket.send(JSON.stringify(handshake));
    };

    socket.onmessage = (event) => {
        try {
            const data = JSON.parse(event.data);
            handleIncomingPacket(data);
        } catch (e) {
            console.error("Error parsing WebSocket frame:", e);
        }
    };

    socket.onclose = (event) => {
        console.log("WebSocket disconnected.");

        // 1008 = VIOLATED_POLICY, which the gateway uses for a bad token. Retrying cannot fix
        // that, so drop the stale token and stop hammering the socket.
        if (event.code === 1008) {
            console.warn("Pairing rejected - ask the host for a fresh invite link.");
            try { localStorage.removeItem('cm_pairing_token'); } catch (e) { /* private mode */ }
            connectionBadge.textContent = "Not paired";
            connectionBadge.className = "badge badge-disconnected";
            return;
        }

        connectionBadge.textContent = "Offline";
        connectionBadge.className = "badge badge-disconnected";

        // Reconnect after 3 seconds
        setTimeout(connectWebSocket, 3000);
    };

    socket.onerror = (error) => {
        console.error("WebSocket error:", error);
    };
}

// --- Incoming Message Handler ---
function handleIncomingPacket(data) {
    if (data.type === "chat_msg") {
        const sender = data.sender;
        const content = data.content;
        const channel = data.channel;
        const timestamp = new Date(data.timestamp || Date.now());

        // Check if message is an emergency alert
        if (content.startsWith('/alert ') || channel === '#gctu-announcements') {
            const alertText = content.replace('/alert ', '');
            // showEmergencyAlert() intentionally not called: it is a full-screen interrupt driven by
            // unauthenticated peer input, so any peer could disrupt every connected client at will.
            // The announcement still appears in the message list, marked unverified.
            appendAlertMessage(sender, alertText, timestamp);
        } else if (channel === currentChannel) {
            appendChatMessage(sender, content, timestamp);
        }
    }
}

// --- Send Message ---
function sendMessage() {
    const text = messageInput.value.trim();
    if (!text || !socket || socket.readyState !== WebSocket.OPEN) return;

    // Build chat message payload
    const payload = {
        type: "chat_msg",
        content: text,
        channel: currentChannel
    };

    socket.send(JSON.stringify(payload));
    messageInput.value = '';

    // Append outgoing message directly
    appendChatMessage('You', text, new Date(), true);
}

sendBtn.addEventListener('click', sendMessage);
messageInput.addEventListener('keypress', (e) => {
    if (e.key === 'Enter') sendMessage();
});

// --- UI rendering helpers ---
function appendChatMessage(sender, content, date, isOutgoing = false) {
    const msgDiv = document.createElement('div');
    msgDiv.className = `message ${isOutgoing ? 'outgoing' : ''}`;

    const metaDiv = document.createElement('div');
    metaDiv.className = 'message-meta';
    
    const nameSpan = document.createElement('span');
    nameSpan.className = 'sender';
    nameSpan.textContent = sender;

    const timeSpan = document.createElement('span');
    timeSpan.className = 'time';
    timeSpan.textContent = date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

    metaDiv.appendChild(nameSpan);
    // No role badge here. This used to derive a role from the sender's display name
    // (sender.includes('Lecturer')) and label everyone else "Student" - so any peer could award
    // themselves a lecturer badge by renaming, and every other peer was shown as verified
    // membership they had never proved. Role badges return when they come from a verified
    // credential, not from a string.
    metaDiv.appendChild(timeSpan);

    const bodyDiv = document.createElement('div');
    bodyDiv.className = 'msg-body';
    bodyDiv.textContent = content;

    msgDiv.appendChild(metaDiv);
    msgDiv.appendChild(bodyDiv);

    messagesList.appendChild(msgDiv);
    messagesList.scrollTop = messagesList.scrollHeight;
}

function appendAlertMessage(sender, content, date) {
    const msgDiv = document.createElement('div');
    msgDiv.className = 'message priority-alert';

    const metaDiv = document.createElement('div');
    metaDiv.className = 'message-meta';
    
    const nameSpan = document.createElement('span');
    nameSpan.className = 'sender';
    nameSpan.textContent = '📢 ' + sender;

    // Not 'OFFICIAL'. Nothing here has been verified: this renders whatever arrived over the
    // mesh, and no credential check exists yet. Labelling it official let any peer impersonate
    // a campus announcement. Restore an authority badge only when a verified credential backs it.
    const roleBadge = document.createElement('span');
    roleBadge.className = 'role-badge role-unverified';
    roleBadge.textContent = 'UNVERIFIED';

    const timeSpan = document.createElement('span');
    timeSpan.className = 'time';
    timeSpan.textContent = date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

    metaDiv.appendChild(nameSpan);
    metaDiv.appendChild(roleBadge);
    metaDiv.appendChild(timeSpan);

    const bodyDiv = document.createElement('div');
    bodyDiv.className = 'msg-body';
    bodyDiv.textContent = `ALERT: ${content}`;

    msgDiv.appendChild(metaDiv);
    msgDiv.appendChild(bodyDiv);

    messagesList.appendChild(msgDiv);
    messagesList.scrollTop = messagesList.scrollHeight;
}

function appendSystemMessage(text) {
    const msgDiv = document.createElement('div');
    msgDiv.className = 'message system-msg';

    const bodyDiv = document.createElement('div');
    bodyDiv.className = 'msg-body';
    bodyDiv.textContent = text;

    msgDiv.appendChild(bodyDiv);
    messagesList.appendChild(msgDiv);
    messagesList.scrollTop = messagesList.scrollHeight;
}

// --- Emergency Alert Banner ---
function showEmergencyAlert(sender, message) {
    alertMessage.textContent = `[${sender}]: ${message}`;
    alertBanner.className = 'alert-banner-visible';
    
    // Auto-dismiss after 15 seconds
    setTimeout(dismissAlert, 15000);
}

function dismissAlert() {
    alertBanner.className = 'alert-banner-hidden';
}

alertClose.addEventListener('click', dismissAlert);

// --- Tab & Channel Navigation ---
function setupTabSwitching() {
    showChatTab.addEventListener('click', () => {
        showChatTab.classList.add('active');
        showMapTab.classList.remove('active');
        chatTabContent.classList.add('active');
        mapTabContent.classList.remove('active');
    });

    showMapTab.addEventListener('click', () => {
        showMapTab.classList.add('active');
        showChatTab.classList.remove('active');
        mapTabContent.classList.add('active');
        chatTabContent.classList.remove('active');
        
        // Re-invalidate map sizes when opened
        if (map) {
            setTimeout(() => map.invalidateSize(), 100);
        }
    });

    // Channels switching
    document.querySelectorAll('.channel-item').forEach(item => {
        item.addEventListener('click', (e) => {
            e.preventDefault();
            document.querySelectorAll('.channel-item').forEach(c => c.classList.remove('active'));
            item.classList.add('active');
            
            currentChannel = item.getAttribute('data-channel');
            document.getElementById('current-channel-title').textContent = currentChannel;
            
            // Channel descriptions
            const desc = document.getElementById('channel-desc');
            if (currentChannel === '#general') {
                desc.textContent = "GCTU general campus-wide offline chatroom.";
            } else if (currentChannel === '#computing-cis') {
                desc.textContent = "Topic channel for Computing and CIS faculty students.";
            } else if (currentChannel === '#gctu-announcements') {
                desc.textContent = "Read-Only Official Announcements & emergency alerts.";
            }
            
            messagesList.innerHTML = '';
            appendSystemMessage(`Entered channel ${currentChannel}`);
        });
    });
}

// --- Offline OpenStreetMap Mapping ---
function setupMap() {
    if (map) return;

    console.log("Initializing Offline Map...");
    
    // GCTU Main Campus center coordinates
    const gctuCenter = [CAMPUSES.main.lat, CAMPUSES.main.lon];
    
    // Initialize leaflet map
    map = L.map('map').setView(gctuCenter, 16);

    // Serve tiles offline from the Ktor server endpoint
    offlineTileLayer = L.tileLayer('/tiles/{z}/{x}/{y}.png', {
        maxZoom: 18,
        minZoom: 14,
        attribution: 'Offline OpenStreetMap (GCTU Campus Mesh)'
    }).addTo(map);

    // Draw campus boundary circles
    L.circle([CAMPUSES.main.lat, CAMPUSES.main.lon], {
        color: '#00e5ff',
        fillColor: '#00e5ff',
        fillOpacity: 0.1,
        radius: CAMPUSES.main.radius
    }).addTo(map).bindPopup("GCTU Main Campus (Tesano)");

    L.circle([CAMPUSES.abeka.lat, CAMPUSES.abeka.lon], {
        color: '#ff4757',
        fillColor: '#ff4757',
        fillOpacity: 0.1,
        radius: CAMPUSES.abeka.radius
    }).addTo(map).bindPopup("Abeka Campus (SITB)");

    // Plot our own marker — suppressed entirely while Ghost Mode is on, matching the
    // "nothing is ever sent" behavior GhostModePreferenceManager documents on the native side.
    renderOwnMarker();
}

let ownMarker = null;

function renderOwnMarker() {
    if (!map) return;
    if (ownMarker) {
        map.removeLayer(ownMarker);
        ownMarker = null;
    }
    if (isGhostMode) return; // Ghosted: no marker, no position leaves the client.

    const gctuCenter = [CAMPUSES.main.lat, CAMPUSES.main.lon];
    const myPin = L.divIcon({
        className: 'custom-pin',
        html: 'You',
        iconSize: [30, 30]
    });
    ownMarker = L.marker(gctuCenter, { icon: myPin }).addTo(map).bindPopup(`You (${myUsername}) - ${myRole}`);
}

/**
 * Toggles Ghost Mode: hides/shows this client's own marker and persists the choice. Real
 * peer-location mesh broadcast doesn't exist yet (see updatePeerMapPresence below), so today
 * this only controls our own marker and the flag sent in the handshake — it's the plumbing
 * ready for when peer positions are actually transmitted over the mesh.
 */
function setGhostMode(enabled) {
    isGhostMode = enabled;
    localStorage.setItem('campusmesh_ghost_mode', enabled ? 'true' : 'false');
    renderOwnMarker();
    updateGhostModeButton();
    if (socket && socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify({ type: 'ghost_mode', peerID: myPeerID, enabled }));
    }
}

// Peer positions are deliberately NOT rendered.
//
// This previously plotted every peer at a random offset around campus and inferred their role from
// a substring of their peer ID ("Lecturer" => lecturer pin). Both were fabricated: a mesh
// connection establishes neither someone's position nor their student status, and showing invented
// ones made unverified peers look located and unverified roles look authorized.
//
// Positions may only be drawn from a position the peer explicitly shared, and role badges only
// from a verified credential. Until peer-location broadcast and credential verification exist,
// peers appear in the participant list without a map marker.
function updatePeerMapPresence(peerID) {
    // Intentionally a no-op: no position evidence exists for remote peers yet.
}
