# Lapis Cloud -- production image for lapis-server, with the lapis-client production bundle baked
# in (served by the server itself via LAPIS_CLIENT_DIST_ROOT, same convention the pre-Docker
# systemd deployment already used -- see deploy/production/README.adoc).
#
# Two-stage build: JDK 25 build stage (Gradle needs a full JDK; also builds the Kotlin/JS client,
# which needs Node -- the Kotlin Gradle plugin downloads it into the Gradle cache automatically,
# no separate Node image needed) -> slim JDK 25 JRE runtime stage. Debian-based (not Alpine/musl):
# this app renders PDFs via Apache PDFBox (invoice/donation-receipt generation, see
# BeitragsrechnungPdfGenerator), which needs AWT font/rendering support that is unreliable on
# musl-based images without extra fontconfig plumbing -- not worth the risk for a document-
# generation feature this app actually depends on in production.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Copy build configuration first (Gradle wrapper, settings, version catalog, per-module
# build.gradle.kts) so dependency resolution layers cache independently of source changes.
COPY gradlew gradlew.bat build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY lapis-shared/build.gradle.kts ./lapis-shared/build.gradle.kts
COPY lapis-server/build.gradle.kts ./lapis-server/build.gradle.kts
COPY lapis-client/build.gradle.kts ./lapis-client/build.gradle.kts

# Now the full source tree.
COPY lapis-shared ./lapis-shared
COPY lapis-server ./lapis-server
COPY lapis-client ./lapis-client

# Builds the server's installDist layout (bin/+lib/, the same application-plugin output the prior
# systemd-based deployment used) and the client's minified production webpack bundle in one
# invocation, so both run through the same dependency-resolution pass.
RUN ./gradlew --no-daemon :lapis-server:installDist :lapis-client:jsBrowserProductionWebpack

FROM eclipse-temurin:25-jre AS runtime

RUN groupadd --system lapiscloud && useradd --system --gid lapiscloud --home /app lapiscloud

WORKDIR /app
COPY --from=build /workspace/lapis-server/build/install/lapis-server ./server
COPY --from=build /workspace/lapis-client/build/kotlin-webpack/js/productionExecutable ./client
COPY --from=build /workspace/lapis-client/build/processedResources/js/main/index.html ./client/index.html

# Review finding: the webpack productionExecutable directory also contains main.bundle.js.map
# (~9.5MB) and main.bundle.js.LICENSE.txt. The Ktor server's staticFiles("/", clientDistRoot) would
# happily serve both to anyone -- unnecessary attack surface (reveals internal Kotlin/JS module/file
# structure via the source map) and image bloat, not a secrets leak but no reason to ship it either.
RUN rm -f ./client/main.bundle.js.map ./client/main.bundle.js.LICENSE.txt

# LAPIS_DOCUMENT_STORAGE_ROOT target -- a named volume is mounted here in production compose so
# uploaded documents survive container recreation (image rebuilds, redeploys).
RUN mkdir -p /app/document-storage && chown -R lapiscloud:lapiscloud /app

USER lapiscloud

ENV LAPIS_CLIENT_DIST_ROOT=/app/client
ENV LAPIS_DOCUMENT_STORAGE_ROOT=/app/document-storage

EXPOSE 8080

ENTRYPOINT ["/app/server/bin/lapis-server"]
