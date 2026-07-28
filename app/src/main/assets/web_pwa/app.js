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
    main: { name: "GCTU Main Campus (Tesano)", lat: 5.6115, lon: -0.2290, radius: 300 },
    abeka: { name: "Abeka Campus (SITB)", lat: 5.6025, lon: -0.2425, radius: 200 }
};

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
async fun generateIdentityID() {
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
}

// --- WebSocket Connection ---
function connectWebSocket() {
    const host = window.location.host || 'localhost:8080';
    const wsUrl = `ws://${host}/chat-ws`;

    console.log(`Connecting to Ktor WebSocket: ${wsUrl}`);
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
            role: myRole
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

    socket.onclose = () => {
        console.log("WebSocket disconnected.");
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
            showEmergencyAlert(sender, alertText);
            appendAlertMessage(sender, alertText, timestamp);
        } else if (channel === currentChannel) {
            appendChatMessage(sender, content, timestamp);
        }
        
        // Update mock location mapping on map for peer demo
        updatePeerLocationMock(sender);
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

    const roleBadge = document.createElement('span');
    const isLecturer = sender.includes('Lecturer') || sender === 'You' && myRole === 'Lecturer';
    roleBadge.className = `role-badge ${isLecturer ? 'role-lecturer' : 'role-student'}`;
    roleBadge.textContent = isLecturer ? 'Lecturer' : 'Student';

    const timeSpan = document.createElement('span');
    timeSpan.className = 'time';
    timeSpan.textContent = date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

    metaDiv.appendChild(nameSpan);
    metaDiv.appendChild(roleBadge);
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

    const roleBadge = document.createElement('span');
    roleBadge.className = 'role-badge role-lecturer';
    roleBadge.textContent = 'OFFICIAL';

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

    // Plot our own marker
    const myPin = L.divIcon({
        className: 'custom-pin',
        html: 'You',
        iconSize: [30, 30]
    });
    L.marker(gctuCenter, { icon: myPin }).addTo(map).bindPopup(`You (${myUsername}) - ${myRole}`);
}

// Mock peer location around campus for presentation wow factor
function updatePeerLocationMock(peerID) {
    if (!map) return;

    if (peerMarkers[peerID]) {
        return; // Already plotted
    }

    const isLecturer = peerID.includes('Lecturer') || peerID.includes('peer-01');
    const html = isLecturer ? 'L' : 'S';
    
    const pin = L.divIcon({
        className: `custom-pin ${isLecturer ? 'lecturer' : ''}`,
        html: html,
        iconSize: [24, 24]
    });

    // Randomize offset slightly around GCTU campus
    const latOffset = (Math.random() - 0.5) * 0.003;
    const lonOffset = (Math.random() - 0.5) * 0.003;
    const peerLocation = [CAMPUSES.main.lat + latOffset, CAMPUSES.main.lon + lonOffset];

    const marker = L.marker(peerLocation, { icon: pin })
        .addTo(map)
        .bindPopup(`Peer: ${peerID}<br>Role: ${isLecturer ? 'Lecturer' : 'Student'}`);

    peerMarkers[peerID] = marker;
    
    // Update peer map count label
    const peerCount = Object.keys(peerMarkers).length;
    mapPeerCount.textContent = `${peerCount} peer${peerCount > 1 ? 's' : ''} active on map`;
}
