# Build the SPA and serve the static bundle with nginx; /api is proxied to the api service.
#
# node pinned by digest (OD-11, change 6). Tag node:22.23.4-alpine (node v22.23.4 >= 22.22, required by
# react-router 8.5.0 and vite 8) resolved to this digest on 2026-10-22. Rebuild+repin when bumping node.
FROM node:22.23.4-alpine@sha256:ce8d97f7cfc7c5386421eb932df20a29df99b1a7f469f6157d5c9956702ffadc AS build
WORKDIR /ui
COPY package.json package-lock.json* ./
# The lockfile is authoritative and reproducible; `npm ci` ONLY (SF-5) — the build FAILS if the lockfile is out
# of sync with package.json rather than silently falling back to `npm install` and drifting.
RUN npm ci
COPY . .
RUN npm run build

# nginx runtime pinned by digest (SF-5, recorded in D-065). Tag nginx:1.27-alpine resolved to this digest on
# 2026-10-25. Rebuild + repin when bumping nginx.
FROM nginx:1.27-alpine@sha256:65645c7bb6a0661892a8b03b89d0743208a18dd2f3f17a54ef4b76fb8e2f2a10 AS run
COPY security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /ui/dist /usr/share/nginx/html
EXPOSE 80
