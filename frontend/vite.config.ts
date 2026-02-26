import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  build: {
    //outDir: '../src/main/resources/static'
    outDir: 'dist'
  }
})
