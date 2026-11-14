import http from 'node:http';

/**
 * R1-PILOT-06 isolated, Node-native readiness check for the prebuilt Vite
 * Preview. Do not use the Spring test JVM's HttpClient for this Windows
 * cross-process check: the browser test uses Node/Chromium on loopback.
 * No remote host, dynamic URL, credentials or response body is logged.
 */
const url='http://127.0.0.1:5176/connected-reception-preview.html';
const expected='Connected Reception (Disposable QA Only)';
let last='not-started';
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));

async function once(){
  return await new Promise(resolve=>{
    let complete=false;
    const finish=(value,reason)=>{
      if(complete)return;
      complete=true;
      last=reason;
      resolve(value);
    };
    const req=http.get(url,{
      agent:false,headers:{Accept:'text/html','Cache-Control':'no-store'},
    },response=>{
      const status=response.statusCode??0;
      const type=String(response.headers['content-type']??'');
      let html='';
      response.on('data',chunk=>{
        // Bounded read; never echo the HTML or a raw HTTP response.
        html+=chunk.toString('utf8');
        if(html.length>32768){
          finish(false,'oversized-response');
          req.destroy();
        }
      });
      response.on('end',()=>{
        const ok=status===200&&type.includes('text/html')
          &&html.includes(expected);
        finish(ok,ok?'verified-html':'http-'+status+'-or-unexpected-html');
      });
      response.on('error',()=>finish(false,'response-error'));
    });
    req.setTimeout(2000,()=>{
      finish(false,'read-timeout');
      req.destroy();
    });
    req.on('error',()=>finish(false,'connection-error'));
  });
}

let ok=false;
for(let attempt=0;attempt<12;attempt++){
  ok=await once();
  if(ok)break;
  await delay(300);
}
if(ok){
  process.stdout.write('PILOT06_NODE_PREVIEW_READY\n');
}else{
  process.stderr.write('PILOT06_NODE_PREVIEW_NOT_READY='+last+'\n');
  process.exitCode=1;
}
