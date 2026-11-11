import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: 'dist-opd-journey',
    rollupOptions: { input: 'opd-journey-preview.html' },
  },
});
