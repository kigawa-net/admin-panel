FROM gradle:8.14.3-jdk21 AS builder

WORKDIR /app
COPY . .
RUN gradle :composeApp:wasmJsBrowserDistribution --no-daemon

FROM nginx:alpine
COPY --from=builder /app/composeApp/build/dist/wasmJs/productionExecutable /usr/share/nginx/html
COPY k8s/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
