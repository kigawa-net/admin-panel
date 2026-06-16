FROM gradle:8.14.3-jdk21 AS builder

ENV SDKMAN_DIR="/root/.sdkman"
RUN curl -s "https://get.sdkman.io" | bash && \
    bash -c "source $SDKMAN_DIR/bin/sdkman-init.sh && sdk install kobweb"

WORKDIR /app
COPY . .
WORKDIR /app/site
RUN bash -c "source $SDKMAN_DIR/bin/sdkman-init.sh && kobweb export --layout static --notty"

FROM nginx:alpine
COPY --from=builder /app/site/.kobweb/site /usr/share/nginx/html
COPY k8s/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
