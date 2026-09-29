FROM node:26-alpine AS build
WORKDIR /app
COPY meterly-web/package*.json ./
RUN npm ci
COPY meterly-web/ ./
RUN npm run build
FROM nginxinc/nginx-unprivileged:stable-alpine
COPY deploy/nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/meterly-web/browser/ /usr/share/nginx/html/
EXPOSE 8080
