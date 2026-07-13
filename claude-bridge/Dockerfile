FROM node:26-bookworm-slim
WORKDIR /app
COPY package.json package-lock.json tsconfig.json ./
COPY src ./src
RUN npm ci && npm run build && npm prune --omit=dev
ENV PORT=8091
EXPOSE 8091
# CLAUDE_CODE_OAUTH_TOKEN must be provided at runtime
CMD ["node", "dist/server.js"]
