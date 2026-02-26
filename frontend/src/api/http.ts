import axios from 'axios'

const isDev = import.meta.env.DEV

export const http = axios.create({
  baseURL: isDev
    ? 'http://localhost:8080'   // 本地开发走后端
    : '',                       // 生产走 nginx
  timeout: 200000
})