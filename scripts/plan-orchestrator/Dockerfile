FROM node:22-bookworm

ARG CODEX_VERSION=0.144.0-alpha.4

RUN apt-get update \
    && apt-get install -y --no-install-recommends git openjdk-17-jdk-headless ripgrep ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && npm install --global "@openai/codex@${CODEX_VERSION}"

ENV CODEX_HOME=/codex-home
COPY result.schema.json /result.schema.json
WORKDIR /workspace

ENTRYPOINT ["codex", "exec"]
