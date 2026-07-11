FROM golang:1.17-alpine3.15 AS builder
LABEL boringproxy=builder
ARG GOOS="linux"
ARG GOARCH="amd64"
ARG VERSION="0.11"
WORKDIR /build
RUN apk add --no-cache libcap
COPY go.* ./
RUN go mod download
COPY . .
RUN cd cmd/boringproxy && CGO_ENABLED=0 GOOS=${GOOS} GOARCH=${GOARCH} \
	go build -ldflags "-X main.Version=${VERSION}" \
	-o boringproxy
RUN setcap cap_net_bind_service=+ep cmd/boringproxy/boringproxy
FROM scratch
EXPOSE 80 443
WORKDIR /storage
COPY --from=builder /build/cmd/boringproxy/boringproxy /
ENTRYPOINT ["/boringproxy"]
CMD ["version"]