FROM node:22-bookworm AS node-runtime

FROM eclipse-temurin:21-jdk-jammy

ARG CODEX_VERSION=0.144.0-alpha.4

RUN apt-get update \
    && apt-get install -y --no-install-recommends git ripgrep ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY --from=node-runtime /usr/local /usr/local
RUN npm install --global "@openai/codex@${CODEX_VERSION}"

ENV CODEX_HOME=/codex-home
COPY result.schema.json /result.schema.json
WORKDIR /workspace

ENTRYPOINT ["codex", "exec"]
