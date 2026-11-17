# ---- build ----
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY tsconfig.json ./
COPY src ./src
RUN npm run build && npm prune --omit=dev

# ---- runtime ----
FROM node:24-alpine
RUN apk add --no-cache git tini \
 && git config --system --add safe.directory '*'
WORKDIR /app
ENV NODE_ENV=production \
    PORT=4000 \
    DATA_DIR=/app/data \
    PROJECTS_FILE=/app/config/projects.docker.json
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
COPY package.json ./
COPY public ./public
COPY config ./config
COPY demo ./demo
RUN mkdir -p /app/data && chown -R node:node /app/data
USER node
EXPOSE 4000
HEALTHCHECK --interval=60s --timeout=5s --start-period=20s \
  CMD wget -qO- http://localhost:4000/health || exit 1
ENTRYPOINT ["/sbin/tini", "--"]
CMD ["node", "dist/index.js"]
