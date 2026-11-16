# syntax=docker/dockerfile:1
# M-05: build the Angular app, then serve the static files from unprivileged nginx on :8080.
# Caddy is the only published port; it proxies everything that isn't /api, /oauth2 or /login/ here.

FROM node:24.22.3-alpine AS build
ENV NG_CLI_ANALYTICS=false CI=true
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY . .
RUN npx ng build

FROM nginxinc/nginx-unprivileged:1.31.7-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist/offershield-web/browser/ /usr/share/nginx/html/
EXPOSE 8080
