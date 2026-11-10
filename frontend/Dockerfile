FROM node:24-alpine AS build
WORKDIR /app
COPY package.json ./
COPY frontend/package.json frontend/package.json
RUN npm install
COPY frontend frontend
RUN npm run build --workspace frontend

FROM nginx:1.27-alpine
COPY --from=build /app/frontend/dist /usr/share/nginx/html
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
