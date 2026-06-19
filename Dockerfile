# CI で kobweb をビルドし、生成物のみを nginx イメージにコピーする
FROM nginx:alpine
COPY dist /usr/share/nginx/html
COPY k8s/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
