FROM node:20-alpine AS builder

WORKDIR /app

COPY package.json pnpm-lock.yaml* ./

RUN corepack enable
RUN corepack prepare pnpm@latest --activate

RUN pnpm install

COPY . .

RUN pnpm build