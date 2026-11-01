// Browser-test fixture ONLY. This file is outside Android assets and is not bundled in the APK.
(() => {
  const now=Date.UTC(2026,9,31,9,0);
  const titles=['الهدوء على الساحل','موسيقى لجلسة عمل هادئة','من الفكرة إلى المشهد','جولة في القاهرة القديمة','ملاحظات صوتية — أفكار جديدة','لقطة محفوظة من الجهاز'];
  const sizes=[25,6,124,48,3,19];
  window.__calls=[];
  window.__records=titles.map((title,index)=>({id:index+1,title,filename:`demo-${index+1}.${[1,4].includes(index)?'mp3':'mp4'}`,
    format:[1,4].includes(index)?'audio':'video',quality:'720',size:sizes[index]*1024*1024,createdAt:now-index*86400000,
    sourceUrl:index===5?'':`https://example.com/watch?v=demo${index+1}`,status:'completed',progress:100,message:'',
    imported:index===5,missing:false,thumbnail:`https://example.test/thumb/${index+1}.svg`}));
  window.__records.push({id:7,title:'كواليس تصميم البيئة',format:'video',quality:'1080',size:0,createdAt:now+1000,
    sourceUrl:'https://example.com/watch?v=demo7',status:'downloading',progress:68,message:'جاري التنزيل • 2.4 MiB/s'},
    {id:8,title:'فيديو يحتاج إعادة المحاولة',format:'video',quality:'best',size:0,createdAt:now+2000,
    sourceUrl:'https://example.com/watch?v=demo8',status:'failed',progress:0,message:'انقطع الاتصال. يمكنك إعادة المحاولة.'});
  const record=(method,args)=>window.__calls.push({method,args:Array.from(args)});
  const emit=(type,data)=>window.AppNative.receive({type,data});
  window.__snapshot=()=>emit('snapshot',{items:window.__records,version:'2.1.0',buildType:'debug',engineVersion:'المحرك المرفق'});
  window.AndroidNative={
    ready(){record('ready',arguments);setTimeout(window.__snapshot,20);},
    inspectUrl(raw,requestId){record('inspectUrl',arguments);setTimeout(()=>emit('inspect',{
      requestId,url:raw,title:'رحلة قصيرة — تجربة تنزيل',duration:183,uploader:'بيانات تجريبية',platform:'Demo',
      thumbnail:'https://example.test/thumb/1.svg',qualities:[1080,720,480,360]}),40);},
    startDownload(){record('startDownload',arguments);emit('message',{message:'أُضيف إلى طابور التنزيل.'});},
    paste(){record('paste',arguments);emit('paste',{text:'https://example.com/video/demo'});},
    refreshLibrary(){record('refreshLibrary',arguments);window.__snapshot();},
    updateEngine(){record('updateEngine',arguments);emit('engineUpdating',{busy:true});setTimeout(()=>emit('engineUpdating',{busy:false}),50);}
  };
  ['setTheme','play','edit','share','sourceAction','requestDelete','retry','cancel','importFiles','exportLibrary'].forEach(method=>{
    window.AndroidNative[method]=function(){record(method,arguments);};
  });
})();
