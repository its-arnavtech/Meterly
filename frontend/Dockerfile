FROM node:22-alpine AS build
WORKDIR /workspace
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM nginx:1.29-alpine
RUN apk upgrade --no-cache
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist/meterly-web/browser /usr/share/nginx/html
EXPOSE 4200
HEALTHCHECK --interval=10s --timeout=3s --retries=20 CMD wget -q -O /dev/null http://127.0.0.1:4200/ || exit 1
