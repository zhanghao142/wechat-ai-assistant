import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { resolve } from 'path';

export default defineConfig({
  plugins: [react()],
  build: {
    rollupOptions: {
      input: {
        main: resolve(__dirname, 'index.html'),
        confirm: resolve(__dirname, 'confirm.html'),
        remind: resolve(__dirname, 'remind.html')
      }
    }
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
        // SSE：避免代理缓冲 / 过早超时
        timeout: 0,
        proxyTimeout: 0
      }
    },
    watch: {
      ignored: ['**/src-tauri/**']
    }
  }
});
