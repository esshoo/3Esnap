'use strict';
(() => {
  const C = window.SnapCore;
  const $ = id => document.getElementById(id);
  const storage = {
    get(key, fallback) { try { return localStorage.getItem(`3esnap.${key}`) || fallback; } catch (_) { return fallback; } },
    set(key, value) { try { localStorage.setItem(`3esnap.${key}`, String(value)); } catch (_) { /* Device storage may be full. */ } }
  };
  const state = { rows: [], video: null, tab: 'download', filter: 'all', query: '', sort: 'newest',
    view: storage.get('view', 'grid') === 'list' ? 'list' : 'grid', limit: 60, requestId: '', sheetId: null,
    theme: storage.get('theme', 'dark'), inspecting: false, engineBusy: false };
  let toastTimer, lastFocus;
  const svgNS = 'http://www.w3.org/2000/svg';
  function icon(name) {
    const svg = document.createElementNS(svgNS, 'svg'); svg.classList.add('icon'); svg.setAttribute('aria-hidden','true');
    const use = document.createElementNS(svgNS, 'use'); use.setAttribute('href',`#i-${name}`); svg.append(use); return svg;
  }
  function el(tag, className, text) { const node = document.createElement(tag); if(className) node.className=className; if(text !== undefined) node.textContent=String(text); return node; }
  function actionButton(label, name, action, className = 'icon-button') {
    const b=el('button',className); b.type='button'; b.setAttribute('aria-label',label); b.title=label;
    b.append(icon(name)); b.addEventListener('click',action); return b;
  }
  function call(method, ...args) {
    try {
      if(window.AndroidNative && typeof window.AndroidNative[method] === 'function') { window.AndroidNative[method](...args); return true; }
      toast('دي معاينة للواجهة. الوظيفة دي بتشتغل داخل تطبيق أندرويد.');
    } catch(e) { showError(e.message || 'تعذر تنفيذ العملية.'); }
    return false;
  }
  function toast(message) { clearTimeout(toastTimer); $('toast').textContent=message || ''; $('toast').hidden=false; toastTimer=setTimeout(() => $('toast').hidden=true,4500); }
  function showError(message) { $('errorText').textContent=message || 'حدث خطأ غير متوقع.'; $('errorBox').hidden=false; toast(message || 'تعذر تنفيذ العملية.'); }
  function clearError() { $('errorBox').hidden=true; }
  function setBusy(busy) { state.inspecting=busy; $('inspectButton').disabled=busy; $('inspectSpinner').hidden=!busy; $('inspectLabel').textContent=busy?'جاري فحص الرابط…':'جلب خيارات التنزيل'; }
  function setTab(tab) {
    if(!['download','library','settings'].includes(tab)) return;
    closeSheet(); state.tab=tab; storage.set('tab',tab);
    document.querySelectorAll('.panel').forEach(p => p.hidden=p.id !== `panel-${tab}`);
    document.querySelectorAll('.nav-item').forEach(b => {
      const selected=b.dataset.tab===tab; b.classList.toggle('active',selected);
      if(selected) b.setAttribute('aria-current','page'); else b.removeAttribute('aria-current');
    });
    window.scrollTo(0,0);
  }
  function applyTheme(value) {
    state.theme=['dark','light','system'].includes(value)?value:'dark'; storage.set('theme',state.theme);
    const light=state.theme==='light' || (state.theme==='system' && window.matchMedia('(prefers-color-scheme: light)').matches);
    document.documentElement.dataset.theme=light?'light':'dark'; $('themeSelect').value=state.theme;
    if(window.AndroidNative) call('setTheme',light);
  }
  function inspect() {
    const raw=$('urlInput').value.trim();
    if(!raw) { showError('الصق رابط الفيديو الأول.'); $('urlInput').focus(); return; }
    clearError(); state.video=null; $('resultCard').hidden=true;
    state.requestId=`${Date.now()}-${Math.random().toString(36).slice(2,8)}`;
    setBusy(true); storage.set('draft',raw);
    if(!call('inspectUrl',raw,state.requestId)) setBusy(false);
  }
  function setImage(img, url) {
    img.src=C.safeImage(url);
    img.addEventListener('error',() => { if(!img.src.endsWith('/media-placeholder.svg')) img.src='media-placeholder.svg'; },{once:true});
  }
  function renderResult(data) {
    state.video=data; setBusy(false);
    $('resultTitle').textContent=data.title || 'فيديو بدون عنوان';
    $('resultAuthor').textContent=data.uploader || 'مصدر عام'; $('resultPlatform').textContent=data.platform || 'وسائط';
    $('resultDuration').textContent=C.formatDuration(data.duration); setImage($('resultThumb'),data.thumbnail);
    $('formatSelect').value=storage.get('format','video')==='audio'?'audio':'video';
    setFormat(); $('resultCard').hidden=false;
    $('resultCard').scrollIntoView({behavior:'smooth',block:'start'});
  }
  function setFormat() {
    const type=$('formatSelect').value; storage.set('format',type);
    $('qualitySelect').replaceChildren();
    if(type==='audio') { $('qualitySelect').append(new Option('MP3 · 192 kbps','192')); $('qualitySelect').disabled=true; }
    else {
      $('qualitySelect').disabled=false; $('qualitySelect').append(new Option('أفضل جودة متاحة','best'));
      const qualities=[...new Set((state.video?.qualities || []).map(Number).filter(n=>Number.isInteger(n)&&n>=144&&n<=4320))].sort((a,b)=>b-a);
      qualities.forEach(q=>$('qualitySelect').append(new Option(`${q}p`,String(q))));
      const saved=storage.get('quality','best'); $('qualitySelect').value=qualities.includes(Number(saved))?saved:'best';
    }
  }
  function startDownload() {
    if(!state.video) return;
    clearError(); const type=$('formatSelect').value,quality=$('qualitySelect').value;
    if(type==='video') storage.set('quality',quality);
    if(call('startDownload',state.video.url,type,quality,state.video.title)) {
      $('downloadButton').disabled=true; setTimeout(()=>$('downloadButton').disabled=false,800);
    }
  }
  function receive(event) {
    if(!event || !event.data) return;
    const d=event.data;
    switch(event.type) {
      case 'snapshot':
        state.rows=Array.isArray(d.items)?d.items:[];
        $('appVersion').textContent=`${d.version || '2.0.0'}${d.buildType==='debug'?' · DEBUG':''}`;
        $('engineVersion').textContent=d.engineVersion || 'المحرك المرفق';
        renderLibrary();renderQueue();break;
      case 'inspect': if(d.requestId===state.requestId) renderResult(d);break;
      case 'inspectError': if(d.requestId===state.requestId) { setBusy(false);showError(d.message); }break;
      case 'paste': $('urlInput').value=d.text || ''; invalidateResult(); storage.set('draft',$('urlInput').value); $('urlInput').focus();break;
      case 'sharedUrl': setTab('download'); $('urlInput').value=d.url || ''; inspect();break;
      case 'tab': setTab(d.tab);break;
      case 'error': showError(d.message);break;
      case 'message': toast(d.message);break;
      case 'engineUpdating': state.engineBusy=Boolean(d.busy); $('updateEngine').disabled=state.engineBusy; $('updateEngine').querySelector('span').textContent=state.engineBusy?'جاري التحديث…':'تحديث المحرك';break;
    }
  }
  function invalidateResult() { state.video=null;state.requestId='';$('resultCard').hidden=true;setBusy(false); }
  function formatDate(value) { try { return new Date(Number(value)).toLocaleDateString('ar-EG',{day:'numeric',month:'short'}); } catch (_) { return ''; } }
  function mediaCard(record) {
    const card=el('article','media-card');card.dataset.id=String(record.id);
    const cover=el('div','media-cover');
    const open=el('button','media-play');open.type='button';open.setAttribute('aria-label',`تشغيل ${record.title}`);open.disabled=Boolean(record.missing);
    const image=el('img');image.alt='';image.loading='lazy';image.referrerPolicy='no-referrer';setImage(image,record.thumbnail);
    const overlay=el('span','play-overlay');overlay.append(icon(record.format==='audio'?'music':'play'));open.append(image,overlay);
    open.addEventListener('click',()=>call('play',Number(record.id)));
    const kind=el('span','media-kind',record.format==='audio'?'AUDIO':'VIDEO');kind.dir='ltr';
    const more=actionButton(`خيارات ${record.title}`,'more',()=>openSheet(record.id),'icon-button card-more');more.dataset.action='menu';
    cover.append(open,kind,more);
    const body=el('div','card-body');const title=el('h3','card-title',record.title || record.filename);title.title=record.filename || record.title;
    const meta=el('div','card-meta');meta.append(el('bdi','',C.formatBytes(record.size)),el('span','',formatDate(record.createdAt)));
    const source=el('div',`saved-link${record.missing?' missing':''}`);
    source.append(icon(record.missing?'close':'link'),el('span','',record.missing?'الملف غير متاح':record.sourceUrl?'المصدر محفوظ':'بدون رابط مصدر'));
    const actions=el('div','card-actions');
    const play=actionButton('تشغيل الملف','play',()=>call('play',Number(record.id)),'card-play-button');play.append(el('span','','تشغيل'));play.disabled=Boolean(record.missing);
    const share=actionButton('مشاركة الملف','share',()=>call('share',Number(record.id)));share.disabled=Boolean(record.missing);share.dataset.action='share';actions.append(play,share);
    body.append(title,meta,source,actions);card.append(cover,body);return card;
  }
  function renderLibrary() {
    const stats=C.stats(state.rows);$('totalCount').textContent=String(stats.count);$('totalSize').textContent=C.formatBytes(stats.bytes);
    $('libraryBadge').textContent=stats.count>99?'99+':String(stats.count);$('libraryBadge').hidden=stats.count===0;
    const files=C.selectFiles(state.rows,state);const grid=$('libraryGrid');grid.classList.toggle('list',state.view==='list');
    $('gridButton').classList.toggle('selected',state.view==='grid');$('listButton').classList.toggle('selected',state.view==='list');
    $('gridButton').setAttribute('aria-pressed',String(state.view==='grid'));$('listButton').setAttribute('aria-pressed',String(state.view==='list'));
    const fragment=document.createDocumentFragment();files.slice(0,state.limit).forEach(r=>fragment.append(mediaCard(r)));grid.replaceChildren(fragment);
    $('libraryEmpty').hidden=files.length>0;$('loadMore').hidden=files.length<=state.limit;
    $('libraryFootnote').hidden=stats.count===0;
    const filtered=stats.count>0;
    $('emptyTitle').textContent=filtered?'مفيش نتائج هنا':'مكان جاهز لتنزيلاتك';
    $('emptyDescription').textContent=filtered?'جرّب اسم تاني، أو غيّر نوع الملفات.':'أول ملف تنزّله هيظهر هنا. عندك ملفات قديمة؟ اضغط «إضافة ملفات».';
    $('emptyAction').textContent=filtered?'إلغاء الفلاتر':'ابدأ تنزيل جديد';
    if(state.sheetId!==null && !state.rows.some(r=>Number(r.id)===Number(state.sheetId))) closeSheet();
  }
  function renderQueue() {
    const jobs=state.rows.filter(r=>r.status!=='completed');$('queueSection').hidden=jobs.length===0;
    $('queueCount').textContent=String(jobs.length);const fragment=document.createDocumentFragment();
    jobs.forEach(record=>{
      const card=el('article','job-card');card.dataset.jobId=String(record.id);
      const top=el('div','job-top');const info=el('div','job-info');info.append(el('h3','',record.title),el('p','job-kind',record.format==='audio'?'MP3 · 192 kbps':`MP4 · ${record.quality==='best'?'AUTO':record.quality+'p'}`));
      const actions=el('div','job-actions');
      if(C.isActive(record.status)) actions.append(actionButton('إلغاء التنزيل','close',()=>call('cancel',Number(record.id))));
      else {
        actions.append(actionButton('إعادة المحاولة','refresh',()=>call('retry',Number(record.id))));
        actions.append(actionButton('إزالة السجل','trash',()=>call('requestDelete',Number(record.id))));
      }
      top.append(info,actions);card.append(top);
      const status=el('p',`job-status ${record.status==='failed'?'failed':''}`,record.message || ({queued:'في الانتظار',downloading:'جاري التنزيل',failed:'تعذر التنزيل',cancelled:'تم الإلغاء'}[record.status] || ''));
      card.append(status);
      if(C.isActive(record.status)) {
        const track=el('div','progress-track'),fill=el('div','progress-fill');const p=Math.max(0,Math.min(100,Number(record.progress)||0));
        fill.style.width=`${p}%`;track.setAttribute('role','progressbar');track.setAttribute('aria-label','تقدم التنزيل');track.setAttribute('aria-valuemin','0');track.setAttribute('aria-valuemax','100');track.setAttribute('aria-valuenow',String(p));track.append(fill);card.append(track);
      }
      fragment.append(card);
    });
    $('queueList').replaceChildren(fragment);
  }
  function sheetAction(label,name,method,args,danger=false,disabled=false) {
    const b=el('button',`sheet-action${danger?' danger':''}`);b.type='button';b.dataset.action=method+(args[1]?`-${args[1]}`:'');b.disabled=disabled;
    b.append(icon(name),el('span','',label));b.addEventListener('click',()=>{closeSheet();call(method,...args);});return b;
  }
  function openSheet(id) {
    const record=state.rows.find(r=>Number(r.id)===Number(id));if(!record) return;
    state.sheetId=Number(id);lastFocus=document.activeElement;
    $('sheetKind').textContent=record.format==='audio'?'ملف صوتي':'ملف فيديو';$('sheetTitle').textContent=record.title || record.filename;
    $('sheetSource').textContent=record.sourceUrl || 'لم يُحفظ رابط مصدر لهذا الملف';
    const args=[Number(id)],actions=$('sheetActions');actions.replaceChildren(
      sheetAction('تشغيل داخل التطبيق','play','play',args,false,Boolean(record.missing)),
      sheetAction('مشاركة الملف','share','share',args,false,Boolean(record.missing)),
      sheetAction('نسخ رابط المصدر','copy','sourceAction',[Number(id),'copy'],false,!record.sourceUrl),
      sheetAction('الانتقال إلى المصدر','external','sourceAction',[Number(id),'open'],false,!record.sourceUrl),
      sheetAction('مشاركة الرابط','link','sourceAction',[Number(id),'share'],false,!record.sourceUrl),
      sheetAction(record.sourceUrl?'تعديل رابط المصدر':'إضافة رابط المصدر','edit','sourceAction',[Number(id),'edit']),
      sheetAction(record.imported?'إزالة من المكتبة فقط':'حذف / إزالة من المكتبة','trash','requestDelete',args,true));
    $('sheetBackdrop').hidden=false;$('fileSheet').hidden=false;document.body.style.overflow='hidden';
    document.querySelector('.app-shell').setAttribute('aria-hidden','true');
    if('inert' in HTMLElement.prototype) document.querySelector('.app-shell').inert=true;
    $('closeSheet').focus();
  }
  function closeSheet() {
    if(state.sheetId===null) return;
    state.sheetId=null;$('sheetBackdrop').hidden=true;$('fileSheet').hidden=true;document.body.style.overflow='';
    document.querySelector('.app-shell').removeAttribute('aria-hidden');document.querySelector('.app-shell').inert=false;
    if(lastFocus && lastFocus.isConnected) lastFocus.focus();
  }
  document.querySelectorAll('[data-tab]').forEach(b=>b.addEventListener('click',()=>setTab(b.dataset.tab)));
  document.querySelectorAll('[data-filter]').forEach(b=>b.addEventListener('click',()=>{
    state.filter=b.dataset.filter;state.limit=60;
    document.querySelectorAll('[data-filter]').forEach(f=>{f.classList.toggle('active',f===b);f.setAttribute('aria-pressed',String(f===b));});renderLibrary();
  }));
  $('inspectForm').addEventListener('submit',e=>{e.preventDefault();inspect();});
  $('urlInput').addEventListener('input',()=>{invalidateResult();storage.set('draft',$('urlInput').value);});
  $('pasteButton').addEventListener('click',()=>call('paste'));
  $('downloadButton').addEventListener('click',startDownload);$('formatSelect').addEventListener('change',setFormat);
  $('librarySearch').addEventListener('input',()=>{state.query=$('librarySearch').value;state.limit=60;renderLibrary();});
  $('sortSelect').addEventListener('change',()=>{state.sort=$('sortSelect').value;state.limit=60;renderLibrary();});
  [['gridButton','grid'],['listButton','list']].forEach(([id,view])=>$(id).addEventListener('click',()=>{state.view=view;storage.set('view',view);renderLibrary();}));
  $('importButton').addEventListener('click',()=>call('importFiles'));$('settingsImport').addEventListener('click',()=>call('importFiles'));
  $('refreshButton').addEventListener('click',()=>{if(call('refreshLibrary')) toast('جاري التحقق من الملفات وتحديث المكتبة…');});
  $('exportButton').addEventListener('click',()=>call('exportLibrary'));
  $('updateEngine').addEventListener('click',()=>call('updateEngine'));
  $('themeSelect').addEventListener('change',()=>applyTheme($('themeSelect').value));
  $('themeQuick').addEventListener('click',()=>applyTheme(document.documentElement.dataset.theme==='dark'?'light':'dark'));
  const themeMedia=window.matchMedia('(prefers-color-scheme: light)');
  const onThemeChange=()=>{if(state.theme==='system') applyTheme('system');};
  if(themeMedia.addEventListener) themeMedia.addEventListener('change',onThemeChange);else themeMedia.addListener(onThemeChange);
  $('emptyAction').addEventListener('click',()=>{
    if(C.stats(state.rows).count===0) {setTab('download');$('urlInput').focus();}
    else {state.query='';$('librarySearch').value='';document.querySelector('[data-filter="all"]').click();}
  });
  $('loadMore').addEventListener('click',()=>{state.limit+=60;renderLibrary();});
  $('dismissError').addEventListener('click',clearError);$('closeSheet').addEventListener('click',closeSheet);$('sheetBackdrop').addEventListener('click',closeSheet);
  document.addEventListener('keydown',e=>{
    if(state.sheetId===null) return;
    if(e.key==='Escape') {closeSheet();e.preventDefault();}
    if(e.key==='Tab') {
      const buttons=[...$('fileSheet').querySelectorAll('button:not(:disabled)')];const first=buttons[0],last=buttons[buttons.length-1];
      if(e.shiftKey && document.activeElement===first) {last.focus();e.preventDefault();}
      else if(!e.shiftKey && document.activeElement===last) {first.focus();e.preventDefault();}
    }
  });
  window.AppNative={receive};
  window.AppUI={handleBack(){if(state.sheetId!==null){closeSheet();return true;}if(state.tab!=='download'){setTab('download');return true;}return false;}};
  $('urlInput').value=storage.get('draft','').slice(0,8192);
  applyTheme(state.theme);renderLibrary();renderQueue();
  setTab(storage.get('tab','download'));
  $('previewBanner').hidden=Boolean(window.AndroidNative);
  if(window.AndroidNative) call('ready');
})();
