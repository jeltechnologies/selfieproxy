FROM --platform=$BUILDPLATFORM golang:1.25-alpine AS builder
LABEL selfieproxy-reverseproxy=builder
ARG TARGETOS
ARG TARGETARCH
ARG VERSION="0.11"
WORKDIR /build
RUN apk add --no-cache libcap
COPY go.* ./
RUN go mod download
COPY . .
RUN cd cmd/boringproxy && CGO_ENABLED=0 GOOS=${TARGETOS} GOARCH=${TARGETARCH} \
	go build -ldflags "-X main.Version=${VERSION}" \
	-o selfieproxy-reverseproxy
RUN setcap cap_net_bind_service=+ep cmd/boringproxy/selfieproxy-reverseproxy
# alpine rather than scratch: selfieproxy-localsites-agent (docker-compose.yaml)
# reuses this same image with a /bin/sh entrypoint wrapper that reads its
# secret from a file before exec'ing the binary (boringproxy's own -secret
# flag takes a literal value, not a file path).
FROM alpine:3.20
EXPOSE 80 443
WORKDIR /storage
COPY --from=builder /build/cmd/boringproxy/selfieproxy-reverseproxy /
ENTRYPOINT ["/selfieproxy-reverseproxy"]
CMD ["version"]