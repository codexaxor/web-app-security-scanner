# Build the SPA and serve the static bundle with nginx; /api is proxied to the api service.
#
# node pinned by digest (OD-11, change 6). Tag node:22.23.4-alpine (node v22.23.4 >= 22.22, required by
# react-router 8.5.0 and vite 8) resolved to this digest on 2026-10-22. Rebuild+repin when bumping node.
FROM node:22.23.4-alpine@sha256:ce8d97f7cfc7c5386421eb932df20a29df99b1a7f469f6157d5c9956702ffadc AS build
WORKDIR /ui
COPY package.json package-lock.json* ./
# The lockfile is authoritative and reproducible; `npm ci` succeeds with the image's bundled npm.
RUN npm ci || npm install
COPY . .
RUN npm run build

FROM nginx:1.27-alpine AS run
COPY security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /ui/dist /usr/share/nginx/html
EXPOSE 80
