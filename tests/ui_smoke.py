#!/usr/bin/env python3
"""Real Chromium UI tests with a MOCK native bridge, not an Android/emulator test."""
from pathlib import Path
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from functools import partial
import os
import json
import threading
import re
import base64
from playwright.sync_api import sync_playwright, expect

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'.test-output'
OUT.mkdir(exist_ok=True)
ASSETS=ROOT/'app/src/main/assets/www'
OFFLINE=os.getenv('UI_TEST_MODE')=='offline-dom'
checks=[]
errors=[]

class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self,*args):
        pass

server=ThreadingHTTPServer(('127.0.0.1',0),partial(QuietHandler,directory=str(ASSETS)))
threading.Thread(target=server.serve_forever,daemon=True).start()

def ok(name):
    checks.append(name)
    print('PASS:',name,flush=True)

def artwork(number):
    palettes=[('#17404b','#77d6d7','#d2e6ca'),('#26334f','#8473ae','#c6b9e2'),('#463826','#bf9460','#edd6ac'),
              ('#233c43','#719a93','#d2e5d4'),('#213f48','#4eaab6','#b4d1d7'),('#3e2d38','#b88481','#e2bca6')]
    a,b,c=palettes[(number-1)%len(palettes)]
    if number in (2,5):
        bars=''.join(f'<rect x="{45+i*17}" y="{88-h/2}" width="7" height="{h}" rx="3" fill="{c}" opacity=".8"/>' for i,h in enumerate([14,28,44,72,48,90,60,32,56,24,14,30,42,18]))
        return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 320 180"><rect width="320" height="180" fill="{a}"/><circle cx="255" cy="30" r="110" fill="{b}" opacity=".25"/>{bars}</svg>'
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 320 180"><defs><linearGradient id="g" x2="0" y2="1"><stop stop-color="{a}"/><stop offset="1" stop-color="{b}"/></linearGradient></defs><rect width="320" height="180" fill="url(#g)"/><circle cx="230" cy="50" r="24" fill="{c}"/><path d="M0 135 90 65 145 112 217 85 320 145V180H0z" fill="{a}"/><path d="M0 150 90 130 175 145 260 130 320 145V180H0z" fill="{c}" opacity=".24"/></svg>'

try:
    with sync_playwright() as p:
        launch={'headless':True,'args':['--no-sandbox']}
        if os.getenv('CHROMIUM_BINARY'):
            launch['executable_path']=os.environ['CHROMIUM_BINARY']
        browser=p.chromium.launch(**launch)
        context=browser.new_context(viewport={'width':412,'height':932},device_scale_factor=1,locale='ar-EG',reduced_motion='reduce')
        context.add_init_script(path=str(ROOT/'tests/fixtures/bridge-mock.js'))
        def image_route(route):
            try: number=int(route.request.url.split('/')[-1].split('.')[0])
            except ValueError: number=1
            route.fulfill(status=200,content_type='image/svg+xml',body=artwork(number))
        context.route('https://example.test/**',image_route)
        page=context.new_page()
        page.on('pageerror',lambda e:errors.append(str(e)))
        def load_page(reload=False):
            if not OFFLINE:
                if reload: page.reload(wait_until='networkidle')
                else: page.goto(f'http://127.0.0.1:{server.server_port}/index.html',wait_until='networkidle')
                return
            # No navigation/network is allowed in some execution sandboxes. Build a
            # local DOM fixture instead. This mode does NOT exercise CSP, browser
            # origin policy, real localStorage persistence, or the Android WebView.
            saved=page.evaluate('window.__testStorage || {}') if reload else {}
            html=(ASSETS/'index.html').read_text()
            html=re.sub(r'<meta[^>]+http-equiv="Content-Security-Policy"[^>]*>','',html)
            html=re.sub(r'<script[^>]+src="[^"]+"[^>]*></script>','',html)
            html=re.sub(r'<link[^>]+href="style.css"[^>]*>','',html)
            html=html.replace('</head>','<style>'+(ASSETS/'style.css').read_text()+'</style></head>')
            for name in ('brand.svg','media-placeholder.svg'):
                uri='data:image/svg+xml;base64,'+base64.b64encode((ASSETS/name).read_bytes()).decode()
                html=html.replace('src="'+name+'"','src="'+uri+'"')
            page.set_content(html)
            page.evaluate("""values => {
                window.__testStorage=values;
                Object.defineProperty(window,'localStorage',{configurable:true,value:{
                    getItem(key){return window.__testStorage[key]??null;},
                    setItem(key,value){window.__testStorage[key]=String(value);},
                    removeItem(key){delete window.__testStorage[key];}
                }});
            }""",saved)
            page.add_script_tag(content=(ROOT/'tests/fixtures/bridge-mock.js').read_text())
            images={str(n):'data:image/svg+xml;base64,'+base64.b64encode(artwork(n).encode()).decode() for n in range(1,7)}
            page.evaluate("images=>{window.__testImages=images;window.__records.forEach(r=>{r.thumbnail=images[r.id]||'';});}",images)
            page.add_script_tag(content=(ASSETS/'core.js').read_text())
            page.add_script_tag(content=(ASSETS/'app.js').read_text())
        load_page()
        expect(page.locator('#totalCount')).to_have_text('6')
        expect(page.locator('#panel-download')).to_be_visible()
        ok('Native snapshot renders six completed files; active/failed jobs stay out of the library')
        expect(page.locator('.job-card')).to_have_count(2)
        ok('Download queue renders progress and retry/cancel controls')
        # Biometric-protected vault has its own navigation entry, backed by a native screen.
        page.locator('#vaultButton').click()
        assert page.evaluate("__calls.some(c=>c.method==='openVault')")
        ok('Dedicated vault tab invokes the native biometric screen')
        page.screenshot(path=str(OUT/'preview-download.png'))
        page.locator('.bottom-nav [data-tab="library"]').click()
        expect(page.locator('.media-card')).to_have_count(6)
        expect(page.locator('#totalSize')).to_have_text('225 MB')
        ok('Library grid and byte totals render correctly')
        expect(page.locator('#categoryFilter option')).to_have_count(3)
        page.locator('#categoryFilter').select_option('تعليم')
        expect(page.locator('.media-card')).to_have_count(1)
        expect(page.locator('[data-id="1"] .category-chip')).to_have_text('تعليم')
        page.locator('#categoryFilter').select_option('all')
        ok('Automatic categories are visible, and category filtering works')
        if OFFLINE:
            page.evaluate("""async () => {
                const pending=[];
                document.querySelectorAll('.media-card').forEach(c=>{
                    const old=c.querySelector('img');
                    if(old){const img=old.cloneNode();img.loading='eager';img.src=window.__testImages[c.dataset.id];old.replaceWith(img);pending.push(img.decode().catch(()=>{}));}
                });
                await Promise.all(pending);
            }""")
        page.screenshot(path=str(OUT/'preview-library-grid.png'))
        page.locator('#listButton').click()
        expect(page.locator('#libraryGrid')).to_have_class('media-grid list')
        ok('Grid/list switch works')
        if OFFLINE:
            page.evaluate("""async () => {
                const pending=[];
                document.querySelectorAll('.media-card').forEach(c=>{
                    const old=c.querySelector('img');
                    if(old){const img=old.cloneNode();img.loading='eager';img.src=window.__testImages[c.dataset.id];old.replaceWith(img);pending.push(img.decode().catch(()=>{}));}
                });
                await Promise.all(pending);
            }""")
        page.screenshot(path=str(OUT/'preview-library-list.png'))
        page.locator('[data-filter="audio"]').click()
        expect(page.locator('.media-card')).to_have_count(2)
        page.locator('#librarySearch').fill('جلسة')
        expect(page.locator('.media-card')).to_have_count(1)
        ok('Audio filtering and Arabic title search work together')
        page.locator('#librarySearch').fill('example.com/watch?v=demo5')
        expect(page.locator('.media-card')).to_have_count(1)
        expect(page.locator('.media-card')).to_have_attribute('data-id','5')
        ok('Stored source URLs are searchable')
        page.locator('#librarySearch').fill('')
        page.locator('[data-filter="all"]').click()
        page.locator('#sortSelect').select_option('size')
        expect(page.locator('.media-card').first).to_have_attribute('data-id','3')
        ok('Size sorting picks the largest file first')
        page.locator('[data-id="1"] [data-action="menu"]').click()
        expect(page.locator('#fileSheet')).to_be_visible()
        expect(page.locator('#sheetSource')).to_have_text('https://example.com/watch?v=demo1')
        page.locator('[data-action="sourceAction-copy"]').click()
        assert page.evaluate("__calls.filter(c=>c.method==='sourceAction').at(-1).args")==[1,'copy']
        ok('Copy-source action sends the correct record ID to Android')
        for action,method,args in [('edit','edit',[1]),('share','share',[1]),('sourceAction-open','sourceAction',[1,'open']),('sourceAction-edit','sourceAction',[1,'edit']),('requestDelete','requestDelete',[1])]:
            page.locator('[data-id="1"] [data-action="menu"]').click()
            page.locator(f'#sheetActions [data-action="{action}"]').click()
            last=page.evaluate(f"__calls.filter(c=>c.method==='{method}').at(-1).args")
            assert last==args
        page.locator('[data-id="1"] [data-action="menu"]').click()
        page.locator('#sheetActions [data-action="crop"]').click()
        assert page.evaluate("__calls.filter(c=>c.method==='crop').at(-1).args")==[1]
        ok('Crop is a separate, one-tap action in the video file menu')
        page.locator('[data-id="1"] [data-action="menu"]').click()
        page.locator('#sheetActions [data-action="editCategory"]').click()
        assert page.evaluate("__calls.filter(c=>c.method==='editCategory').at(-1).args")==[1]
        page.locator('[data-id="1"] [data-action="menu"]').click()
        page.locator('#sheetActions [data-action="hide"]').click()
        assert page.evaluate("__calls.filter(c=>c.method==='hide').at(-1).args")==[1]
        ok('File menu exposes manual categories and biometric hide action')
        ok('Share-file, open-source, edit-source and deletion actions call their native entrypoints')
        page.locator('[data-id="1"] .media-play').click()
        assert page.evaluate("__calls.filter(c=>c.method==='play').at(-1).args")==[1]
        ok('Playback is routed by record ID, not arbitrary paths')
        page.locator('[data-id="6"] [data-action="menu"]').click()
        expect(page.locator('[data-action="sourceAction-copy"]')).to_be_disabled()
        expect(page.locator('[data-action="sourceAction-edit"]')).to_be_enabled()
        page.keyboard.press('Escape')
        expect(page.locator('#fileSheet')).to_be_hidden()
        ok('Imported files without a URL allow adding one; empty URL actions are disabled')
        page.locator('[data-id="1"] [data-action="menu"]').click()
        page.locator('.sheet-action').last.focus()
        page.keyboard.press('Tab')
        expect(page.locator('#closeSheet')).to_be_focused()
        page.keyboard.press('Escape')
        ok('File sheet traps keyboard focus and closes with Escape')
        page.locator('#refreshButton').click()
        assert page.evaluate("__calls.some(c=>c.method==='refreshLibrary')")
        ok('Refresh button requests native file validation')
        page.locator('.bottom-nav [data-tab="settings"]').click()
        page.locator('#themeSelect').select_option('light')
        expect(page.locator('html')).to_have_attribute('data-theme','light')
        page.locator('#exportButton').click()
        page.locator('#settingsImport').click()
        page.locator('#importBackupButton').click()
        assert page.evaluate("__calls.some(c=>c.method==='exportLibrary') && __calls.some(c=>c.method==='importFiles') && __calls.some(c=>c.method==='importLibraryBackup')")
        ok('Settings exposes separate JSON backup restoration and old media imports')
        load_page(reload=True)
        expect(page.locator('html')).to_have_attribute('data-theme','light')
        expect(page.locator('#panel-settings')).to_be_visible()
        page.locator('.bottom-nav [data-tab="library"]').click()
        expect(page.locator('#libraryGrid')).to_have_class('media-grid list')
        ok('Theme, last tab, and list/grid preference survive reinitialization' if OFFLINE else 'Theme, last tab, and list/grid preference persist across reloads')
        page.locator('.bottom-nav [data-tab="settings"]').click()
        page.locator('#themeSelect').select_option('dark')
        page.locator('#updateEngine').click()
        expect(page.locator('#updateEngine')).to_be_enabled()
        assert page.evaluate("__calls.some(c=>c.method==='updateEngine')")
        ok('Engine update feedback returns to an enabled state')
        page.locator('.bottom-nav [data-tab="download"]').click()
        page.locator('#pasteButton').click()
        expect(page.locator('#urlInput')).to_have_value('https://example.com/video/demo')
        page.locator('#inspectButton').click()
        expect(page.locator('#resultCard')).to_be_visible()
        expect(page.locator('#resultDuration')).to_have_text('03:03')
        page.locator('#qualitySelect').select_option('720')
        page.locator('#downloadButton').click()
        assert page.evaluate("__calls.filter(c=>c.method==='startDownload').at(-1).args")==['https://example.com/video/demo','video','720','رحلة قصيرة — تجربة تنزيل']
        ok('Inspect, real-duration formatting, quality selection and enqueue parameters are connected')
        auto=page.evaluate("__calls.filter(c=>c.method==='scheduleAutoDownload').at(-1)")
        assert auto is not None and auto['args'][:2]==['https://example.com/video/demo','video']
        page.locator('#autoDownloadSwitch').uncheck()
        assert page.evaluate("__calls.some(c=>c.method==='cancelQueuedSource')")
        assert page.locator('#settingsAutoDownload').is_checked() is False
        page.locator('#autoDownloadSwitch').check()
        ok('Three-second automatic scheduling is native, optional and synchronized with settings')
        page.locator('#formatSelect').select_option('audio')
        expect(page.locator('#qualitySelect')).to_have_value('192')
        expect(page.locator('#qualitySelect')).to_be_disabled()
        ok('MP3 uses the audio path and a real conversion bitrate')
        # Regression for the reported phone screenshot: media panel is compact and
        # the entire preview/card/queue stay within the actual mobile viewport.
        assert page.locator('#panel-download').evaluate('(n)=>n.classList.contains("has-result")')
        assert page.locator('#panel-download .hero').is_hidden() and page.locator('#inspectForm').is_hidden()
        page.evaluate("""() => {
          document.querySelector('#resultTitle').textContent='عنوان فيديو عربي طويل جداً جداً يتضمن 1.7K views وملفات مطولة للتأكد من التفاف النصوص داخل الشاشة'.repeat(2);
          document.querySelector('.job-info h3').textContent='اسم تنزيل طويل - '.repeat(35);
        }""")
        for width in (320,360,390,412):
            page.set_viewport_size({'width':width,'height':780})
            result=page.evaluate("""() => {
              const item=document.querySelector('#resultCard');
              const bounds=item.getBoundingClientRect();
              return {scroll:document.documentElement.scrollWidth,window:innerWidth,left:bounds.left,right:bounds.right};
            }""")
            assert result['scroll']<=width+1 and result['left']>=-1 and result['right']<=width+1, f'Mobile overflow {width}: {result}'
        page.screenshot(path=str(OUT/'preview-responsive-download.png'),full_page=True)
        ok('Expanded media title, download card and queue stay within 320–412px phones')
        page.set_viewport_size({'width':412,'height':932})

        page.locator('#newLinkButton').click()
        page.locator('#urlInput').fill('https://example.com/new')
        expect(page.locator('#resultCard')).to_be_hidden()
        ok('Editing a URL invalidates previously fetched metadata')
        page.evaluate("AppNative.receive({type:'inspect',data:{requestId:'stale',url:'https://bad.example',title:'STALE',qualities:[]}})")
        expect(page.locator('#resultCard')).to_be_hidden()
        ok('Stale asynchronous inspection results are ignored')
        page.locator('.bottom-nav [data-tab="library"]').click()
        page.evaluate("__records[0].title='<img src=x onerror=\"window.__xss=true\">'; __snapshot()")
        expect(page.locator('[data-id="1"] h3')).to_have_text('<img src=x onerror="window.__xss=true">')
        assert page.evaluate('window.__xss === undefined')
        assert page.locator('[data-id="1"] h3 img').count()==0
        ok('Untrusted media titles are text, not executable HTML')
        page.evaluate('__records[0].missing=true; __snapshot()')
        expect(page.locator('[data-id="1"] .media-play')).to_be_disabled()
        page.locator('[data-id="1"] [data-action="menu"]').click()
        expect(page.locator('#sheetActions [data-action="share"]')).to_be_disabled()
        expect(page.locator('[data-action="sourceAction-copy"]')).to_be_enabled()
        expect(page.locator('[data-action="redownloadRestored"]')).to_be_enabled()
        page.locator('[data-action="redownloadRestored"]').click()
        assert page.evaluate("__calls.filter(c=>c.method==='redownloadRestored').at(-1).args")==[1]
        expect(page.locator('[data-id="1"] .card-play-button')).to_have_text('إعادة تنزيل')
        ok('Missing files retain source links and offer re-download without a video URI')
        page.evaluate("__records[2].hidden=true;__snapshot()")
        expect(page.locator('[data-id="3"]')).to_have_count(0)
        assert 'من الفكرة إلى المشهد' not in page.locator('#libraryGrid').inner_text()
        page.evaluate("__records[2].hidden=false;__snapshot()")
        ok('Hidden media is omitted from library snapshots and cannot leak titles')
        page.evaluate('__records=[]; __snapshot()')
        expect(page.locator('#libraryEmpty')).to_be_visible()
        expect(page.locator('#libraryGrid .media-card')).to_have_count(0)
        ok('Empty library has an explicit first-use state')
        page.evaluate("__records=Array.from({length:137},(_,i)=>({id:i+1,title:'ملف '+(i+1),filename:'file'+i+'.mp4',format:'video',size:1024,createdAt:i,status:'completed',sourceUrl:'',thumbnail:''}));__snapshot()")
        expect(page.locator('.media-card')).to_have_count(60)
        page.locator('#loadMore').click()
        expect(page.locator('.media-card')).to_have_count(120)
        page.locator('#loadMore').click()
        expect(page.locator('.media-card')).to_have_count(137)
        expect(page.locator('#loadMore')).to_be_hidden()
        ok('Library pagination exposes every record (60 → 120 → 137)')
        page.locator('#gridButton').click()
        for width in (320,360,412,768,1024):
            page.set_viewport_size({'width':width,'height':932})
            assert page.evaluate('document.documentElement.scrollWidth <= innerWidth + 1'), f'Horizontal overflow at {width}px'
        ok('No horizontal overflow at 320, 360, 412, 768 or 1024 px')
        assert not errors,errors
        ok('No uncaught JavaScript exceptions')
        browser.close()
finally:
    server.shutdown()

report={'status':'passed','checks':len(checks),'passed':checks,'javascript_errors':errors,
        'mode':'offline-dom' if OFFLINE else 'http-origin',
        'scope':'Chromium UI + mocked Android bridge only. Native playback, real downloads, Android permissions and APK compilation were NOT exercised.'}
(OUT/'ui-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(f'PASS: {len(checks)} browser interaction checks. These are NOT device tests.')
