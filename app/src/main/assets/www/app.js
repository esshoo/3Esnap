document.addEventListener('DOMContentLoaded', () => {
  const urlInput = document.getElementById('urlInput');
  const btnFetch = document.getElementById('btnFetch');
  const btnPaste = document.getElementById('btnPaste');
  const btnSpinner = document.getElementById('btnSpinner');
  const btnText = document.getElementById('btnText');
  const alertBox = document.getElementById('alertBox');
  const alertMsg = document.getElementById('alertMsg');

  // Hide APK banner inside native Android app
  const apkBanner = document.getElementById('apkBanner');
  if (apkBanner) apkBanner.style.display = 'none';

  const resultCard = document.getElementById('resultCard');
  const videoThumb = document.getElementById('videoThumb');
  const videoTitle = document.getElementById('videoTitle');
  const videoDuration = document.getElementById('videoDuration');
  const videoAuthor = document.getElementById('videoAuthor');
  const videoPlatform = document.getElementById('videoPlatform');
  const optionsGrid = document.getElementById('optionsGrid');

  const progressPanel = document.getElementById('progressPanel');
  const jobTitle = document.getElementById('jobTitle');
  const progressPct = document.getElementById('progressPct');
  const progressBarFill = document.getElementById('progressBarFill');
  const downloadSpeed = document.getElementById('downloadSpeed');
  const downloadStatusText = document.getElementById('downloadStatusText');
  const downloadActionContainer = document.getElementById('downloadActionContainer');
  const btnFinalDownload = document.getElementById('btnFinalDownload');

  let currentVideoUrl = '';

  window.showAlert = function(msg) {
    alertMsg.textContent = msg;
    alertBox.style.display = 'flex';
    setLoading(false);
  };

  function hideAlert() {
    alertBox.style.display = 'none';
  }

  function setLoading(loading) {
    if (loading) {
      btnSpinner.style.display = 'inline-block';
      btnText.textContent = 'جاري الفحص...';
      btnFetch.disabled = true;
      hideAlert();
    } else {
      btnSpinner.style.display = 'none';
      btnText.textContent = 'جلب الخيارات';
      btnFetch.disabled = false;
    }
  }

  // Paste from clipboard button
  btnPaste.addEventListener('click', async () => {
    try {
      const text = await navigator.clipboard.readText();
      if (text) {
        urlInput.value = text.trim();
        triggerFetch();
      }
    } catch (e) {
      urlInput.focus();
    }
  });

  // Enter key support
  urlInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      triggerFetch();
    }
  });

  btnFetch.addEventListener('click', triggerFetch);

  function triggerFetch() {
    const url = urlInput.value.trim();
    if (!url) {
      window.showAlert('يرجى إدخال رابط فيديو صالح أولاً.');
      return;
    }

    setLoading(true);
    resultCard.style.display = 'none';
    progressPanel.style.display = 'none';

    // Route to On-Device Native Engine if running inside Android APK
    if (window.AndroidNative && window.AndroidNative.inspectUrl) {
      window.AndroidNative.inspectUrl(url);
    } else {
      // Fallback HTTP
      fetch('/api/inspect', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ url: url })
      })
      .then(res => res.json())
      .then(data => {
        if (data.detail) throw new Error(data.detail);
        renderVideoData(data);
      })
      .catch(err => window.showAlert(err.message || 'حدث خطأ'))
      .finally(() => setLoading(false));
    }
  }

  window.renderVideoData = function(data) {
    setLoading(false);
    currentVideoUrl = data.url;
    videoTitle.textContent = data.title;
    videoThumb.src = data.thumbnail || 'https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?auto=format&fit=crop&w=400&q=80';
    videoDuration.textContent = data.duration || '--:--';
    videoAuthor.textContent = data.uploader || 'غير معروف';
    videoPlatform.textContent = data.platform || 'منصة مدعومة';

    optionsGrid.innerHTML = '';

    (data.options || []).forEach(opt => {
      const item = document.createElement('div');
      item.className = 'option-item';

      const isAudio = opt.type === 'audio';
      const icon = isAudio ? '🎵' : '🎬';

      item.innerHTML = `
        <div class="option-info">
          <div class="option-label">${icon} ${opt.label}</div>
          <div class="option-badge">${opt.badge} (${opt.ext.toUpperCase()})</div>
        </div>
        <button class="btn-download-opt">
          <span>تحميل</span>
          <svg width="14" height="14" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"/>
          </svg>
        </button>
      `;

      item.addEventListener('click', () => {
        startDownload(opt);
      });

      optionsGrid.appendChild(item);
    });

    resultCard.style.display = 'block';
    resultCard.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  };

  function startDownload(opt) {
    progressPanel.style.display = 'block';
    downloadActionContainer.style.display = 'none';
    progressBarFill.style.width = '8%';
    progressPct.textContent = '8%';
    jobTitle.textContent = `جاري معالجة: ${opt.label}...`;
    downloadSpeed.textContent = 'المحرك الداخلي نشط';
    downloadStatusText.textContent = 'جاري التحميل والتجهيز على الهاتف مباشرة...';
    progressPanel.scrollIntoView({ behavior: 'smooth', block: 'nearest' });

    if (window.AndroidNative && window.AndroidNative.startDownload) {
      window.AndroidNative.startDownload(currentVideoUrl, opt.type, opt.quality);
    }
  }

  // Native Engine Callbacks
  window.updateNativeProgress = function(percent, statusText) {
    progressBarFill.style.width = `${percent}%`;
    progressPct.textContent = `${percent}%`;
    downloadStatusText.textContent = statusText;
  };

  window.onNativeDownloadReady = function(filename) {
    progressBarFill.style.width = '100%';
    progressPct.textContent = '100%';
    jobTitle.textContent = 'اكتمل التحميل بنجاح!';
    downloadSpeed.textContent = 'تم الحفظ في الهاتف';
    downloadStatusText.textContent = `تم حفظ الملف في مجلد Downloads: ${filename}`;
  };

  window.onNativeDownloadFailed = function(errorMsg) {
    progressBarFill.style.background = '#ef4444';
    jobTitle.textContent = 'فشلت المعالجة';
    downloadStatusText.textContent = errorMsg;
    window.showAlert(errorMsg);
  };

  // External share intent hook
  window.handleExternalUrl = function(newUrl) {
    if (newUrl && urlInput) {
      urlInput.value = newUrl;
      triggerFetch();
    }
  };

  // Check URL params on launch
  const urlParams = new URLSearchParams(window.location.search);
  const initialShared = urlParams.get('shared_url');
  if (initialShared) {
    window.handleExternalUrl(initialShared);
  }
});
