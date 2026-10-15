import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
  const environment = loadEnv(mode, process.cwd(), '');
  const backend = environment.JUDGE_BACKEND_URL || 'http://127.0.0.1:1234';
  return {
    plugins: [react()],
    base: mode === 'production' ? '/js/app/' : '/',
    build: {
      manifest: true,
      rollupOptions: {
        output: {
          entryFileNames: 'app.js',
          chunkFileNames: 'chunks/[name]-[hash].js',
          assetFileNames: 'assets/[name]-[hash][extname]',
        },
      },
    },
    server: {
      port: 5173,
      strictPort: true,
      proxy: {
        '/auth/': { target: backend, changeOrigin: true },
        '/judge': { target: backend, changeOrigin: true },
        '/details/': { target: backend, changeOrigin: true },
        '/download/': { target: backend, changeOrigin: true },
        '/ws': { target: backend, changeOrigin: true, ws: true },
      },
    },
  };
});
