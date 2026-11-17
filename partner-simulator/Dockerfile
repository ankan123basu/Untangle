# syntax=docker/dockerfile:1
# The simulator has no runtime dependencies: Node 26 runs TypeScript directly (type stripping).
FROM node:26.13.1-alpine3.24
WORKDIR /sim
COPY package.json ./
COPY src ./src
USER node
ENTRYPOINT ["node", "src/cli.ts"]
CMD ["--scenario", "all"]
