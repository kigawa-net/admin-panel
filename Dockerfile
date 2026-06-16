FROM gradle:8.14.3-jdk21 AS builder

WORKDIR /app
COPY . .
RUN gradle :site:kobwebExport --notty --no-daemon

FROM nginx:alpine
COPY --from=builder /app/site/.kobweb/site /usr/share/nginx/html
COPY k8s/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
