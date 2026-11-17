# syntax=docker/dockerfile:1
#
# Multi-stage build for the Untangle SPA.
#   Stage 1 (build): install exact dependencies from package-lock.json and run `vite build`.
#   Stage 2 (runtime): unprivileged nginx serving static files and proxying /api to the backend.

# ---------- Stage 1: build ----------
FROM node:26.13.1-alpine3.24 AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund

COPY . .
RUN npm run build

# ---------- Stage 2: runtime ----------
# The unprivileged image runs nginx as a non-root user and listens on 8080.
FROM nginxinc/nginx-unprivileged:1.31.7-alpine3.24 AS runtime

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=5 \
  CMD wget -q --spider http://127.0.0.1:8080/healthz || exit 1
