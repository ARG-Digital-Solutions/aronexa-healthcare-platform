import {defineConfig} from 'vite';
import react from '@vitejs/plugin-react';

/** Explicit developer-run fictional billing QA preview, never production. */
export default defineConfig({
  plugins:[react()],
  server:{host:'127.0.0.1',port:5178,strictPort:true,
    proxy:{'/api/v1':{target:'http://127.0.0.1:8080',changeOrigin:false}}},
  build:{outDir:'dist-connected-billing-test',
    rollupOptions:{input:'connected-billing-preview.html'}},
});
