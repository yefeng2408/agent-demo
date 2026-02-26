import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'


export default defineConfig({
  plugins: [vue()],
  build: {
    //outDir: '../src/main/resources/static'
    outDir: 'dist'
  },  // 这里需要添加逗号
  server: {
    proxy: {
      '/chat': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
