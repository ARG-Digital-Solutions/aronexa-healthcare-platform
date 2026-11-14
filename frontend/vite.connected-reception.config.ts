import {defineConfig} from 'vite';
import react from '@vitejs/plugin-react';

/**
 * PILOT-06 JUnit starts Spring on a random loopback port. Never accept
 * a remote host or full URL from the runner; only a bounded numeric port.
 */
const pilotPort=process.env.ARONEXA_PILOT06_API_PORT;
if(pilotPort&&(!/^[0-9]{4,5}$/.test(pilotPort)
  ||Number(pilotPort)<1024||Number(pilotPort)>65535))
  throw new Error('Invalid Pilot06 loopback test API port');
const apiTarget='http://127.0.0.1:'+(pilotPort??'8080');

/** Manually invoked test-only preview. NOT included in the standard build. */
export default defineConfig({
  plugins:[react()],
  server:{host:'127.0.0.1',port:5176,strictPort:true,
    proxy:{'/api/v1':{target:apiTarget,changeOrigin:false}}},
  build:{outDir:'dist-connected-registration-test',
    rollupOptions:{input:'connected-reception-preview.html'}},
});
