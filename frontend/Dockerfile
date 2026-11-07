FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ENV VITE_ADMIN_BASE=/admin/
RUN npm run build

FROM nginx:stable-alpine
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist/buyer /usr/share/nginx/html
COPY --from=build /src/dist/admin /usr/share/nginx/html/admin
# 源文件可能只有所有者可读（如从其他工具写入的图片），统一放开读权限，避免 nginx 返回 403。
RUN chmod -R a+rX /usr/share/nginx/html
