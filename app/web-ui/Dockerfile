#1 compiling
# not the Alpine image: the Kotlin/JS build downloads a Node.js binary, built for glibc
FROM gradle:9.6.1-jdk26-noble AS gradle_builder

WORKDIR /web_ui
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY kotlin-js-store ./kotlin-js-store
COPY webpack.config.d ./webpack.config.d
RUN gradle dependencies --no-daemon

COPY src ./src
RUN gradle clean jsBrowserDistribution --no-daemon

#2 serving
FROM nginx:1.30.5-alpine3.24

# runs before the image's own 20-envsubst-on-templates.sh, which renders nginx/default.conf.template
COPY --chmod=755 nginx/00-require-env.sh /docker-entrypoint.d/00-require-env.sh
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=gradle_builder /web_ui/build/dist/js/productionExecutable /usr/share/nginx/html
