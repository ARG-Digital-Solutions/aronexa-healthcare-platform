import {defineConfig} from 'vite';
import react from '@vitejs/plugin-react';

/** Manually invoked test-only preview. NOT included in the standard build. */
export default defineConfig({
  plugins:[react()],
  server:{host:'127.0.0.1',port:5176,strictPort:true,
    proxy:{'/api/v1':{target:'http://127.0.0.1:8080',changeOrigin:false}}},
  build:{outDir:'dist-connected-registration-test',
    rollupOptions:{input:'connected-reception-preview.html'}},
});
