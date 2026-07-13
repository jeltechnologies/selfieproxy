FROM alpine:3.20

RUN apk add --no-cache curl bind-tools

COPY check-prerequisites.sh /check-prerequisites.sh
RUN chmod +x /check-prerequisites.sh

ENTRYPOINT ["/check-prerequisites.sh"]
