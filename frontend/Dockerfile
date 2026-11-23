FROM node:22.23.5-bookworm-slim@sha256:e9f565d32859f0cbfb52125eafe8be39390f24dac302dc3cdf566e8ba3ab21ee AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY angular.json tsconfig*.json ./
COPY public/ ./public/
COPY src/ ./src/
COPY scripts/ ./scripts/
ARG PUBLIC_SITE_ORIGIN=""
ARG PUBLIC_SITE_GUIDES="false"
RUN npm run build

FROM nginx:1.30.7-alpine-slim@sha256:8c950211a641ec5b23f265450517001f6bcca533666120ebe36cb4cff3d00a55
RUN apk upgrade --no-cache
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/autocuidado/routes.conf /etc/nginx/conf.d/00-routes.conf
COPY --from=build /app/dist/autocuidado/browser/ /usr/share/nginx/html/
