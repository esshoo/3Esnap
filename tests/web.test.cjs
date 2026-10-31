'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const C=require('../app/src/main/assets/www/core.js');
const rows=[
  {id:1,status:'completed',title:'درس الرَّسم',filename:'a.mp4',format:'video',size:1024,createdAt:100,sourceUrl:'https://example.com/a'},
  {id:2,status:'completed',title:'صوت هادئ',filename:'b.mp3',format:'audio',size:2048,createdAt:200,sourceUrl:''},
  {id:3,status:'downloading',title:'جاري',format:'video',size:99999,createdAt:300},
  {id:4,status:'failed',title:'فشل',format:'audio',size:99999,createdAt:400},
  {id:5,status:'completed',title:'مفقود',filename:'c.mp4',format:'video',size:9000,createdAt:500,missing:true,sourceUrl:'https://example.org/c'}
];
test('library excludes active and failed jobs',()=>assert.equal(C.selectFiles(rows).length,3));
test('video filter works',()=>assert.deepEqual(C.selectFiles(rows,{filter:'video'}).map(x=>x.id),[5,1]));
test('audio filter works',()=>assert.deepEqual(C.selectFiles(rows,{filter:'audio'}).map(x=>x.id),[2]));
test('search ignores Arabic diacritics',()=>assert.equal(C.selectFiles(rows,{query:'الرسم'})[0].id,1));
test('search includes stored source URL',()=>assert.equal(C.selectFiles(rows,{query:'example.org'})[0].id,5));
test('search includes filenames',()=>assert.equal(C.selectFiles(rows,{query:'b.mp3'})[0].id,2));
test('unknown query returns empty',()=>assert.equal(C.selectFiles(rows,{query:'not here'}).length,0));
test('newest first',()=>assert.deepEqual(C.selectFiles(rows).map(x=>x.id),[5,2,1]));
test('oldest first',()=>assert.deepEqual(C.selectFiles(rows,{sort:'oldest'}).map(x=>x.id),[1,2,5]));
test('largest first',()=>assert.deepEqual(C.selectFiles(rows,{sort:'size'}).map(x=>x.id),[5,2,1]));
test('sorting does not mutate the database snapshot',()=>{const old=JSON.stringify(rows);C.selectFiles(rows,{sort:'name'});assert.equal(JSON.stringify(rows),old);});
test('file statistics exclude missing file bytes',()=>assert.deepEqual(C.stats(rows),{count:3,bytes:3072,videos:2,audio:1}));
test('zero byte formatting',()=>assert.equal(C.formatBytes(0),'0 B'));
test('invalid byte sizes are bounded',()=>{assert.equal(C.formatBytes(-1),'0 B');assert.equal(C.formatBytes(NaN),'0 B');assert.equal(C.formatBytes(Infinity),'0 B');});
test('KB and MB formatting',()=>{assert.equal(C.formatBytes(1024),'1.0 KB');assert.equal(C.formatBytes(30*1024**2),'30 MB');});
test('duration below one hour',()=>assert.equal(C.formatDuration(90),'01:30'));
test('duration above one hour',()=>assert.equal(C.formatDuration(3661),'1:01:01'));
test('unknown duration is not fabricated',()=>assert.equal(C.formatDuration(0),'—'));
test('active states',()=>{assert.equal(C.isActive('queued'),true);assert.equal(C.isActive('downloading'),true);assert.equal(C.isActive('completed'),false);});
test('image URLs only use HTTPS',()=>{assert.equal(C.safeImage('https://example.com/a.jpg'),'https://example.com/a.jpg');assert.equal(C.safeImage('http://example.com/a.jpg'),'media-placeholder.svg');});
test('reject script and file image URLs',()=>{for(const url of ['javascript:alert(1)','file:///sdcard/x','data:text/html,bad']) assert.equal(C.safeImage(url),'media-placeholder.svg');});
test('reject credential-bearing image URLs',()=>assert.equal(C.safeImage('https://user:pass@example.com/a'),'media-placeholder.svg'));
test('empty snapshot is supported',()=>{assert.equal(C.selectFiles([]).length,0);assert.equal(C.stats([]).count,0);});
