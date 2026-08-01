FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-25 AS builder
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B -o clean package -DskipTests

# Not part of the runtime image -- a separate export-only target so the same bundled
# local-website-demo.zip the builder stage already produces (see pom.xml's
# zip-local-website-demo antrun execution) can be pulled onto the host -- see root CLAUDE.md for
# the exact (two-step, scratch-dir-first) export command; never point buildx's local exporter's
# `dest` directly at this repo's root or any directory containing `data/` -- it recursively scans
# the whole destination tree, not just the path(s) it's about to write, and errors out (or worse)
# on anything in there it can't read/write, e.g. this-server-certmagic's uid-1000-only cert dirs.
# Placed *before* the runtime stage below deliberately: with no `target:` set in
# docker-compose.yaml, an ordinary `docker compose build`/`up --build` always builds the *last*
# stage in this file as the image -- if this export stage were last, that plain build would
# silently produce a bare scratch image with no ENTRYPOINT instead of the actual portal ("no
# command specified" at container start).
FROM scratch AS website-zip
COPY --from=builder /build/target/classes/local-website-demo.zip /local-website-demo.zip

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=builder /build/target/selfieproxy-portal.jar ./selfieproxy-portal.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "selfieproxy-portal.jar"]
