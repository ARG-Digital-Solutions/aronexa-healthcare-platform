import {defineConfig} from 'vite';
import react from '@vitejs/plugin-react';

/** Explicit developer-run synthetic doctor preview, never production. */
export default defineConfig({
  plugins:[react()],
  server:{host:'127.0.0.1',port:5177,strictPort:true,
    proxy:{'/api/v1':{target:'http://127.0.0.1:8080',changeOrigin:false}}},
  build:{outDir:'dist-connected-doctor-test',
    rollupOptions:{input:'connected-doctor-preview.html'}},
});
