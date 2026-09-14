FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# Compose 交付固定连接真实 API，不编入演示模式或本机私有变量。
ENV VITE_DEMO_MODE=false VITE_JAVA_LOCAL=false VITE_ADMIN_BASE=/admin/
RUN npm run build

FROM nginx:stable-alpine
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist/buyer /usr/share/nginx/html
COPY --from=build /src/dist/admin /usr/share/nginx/html/admin
