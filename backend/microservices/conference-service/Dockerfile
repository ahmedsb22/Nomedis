# ============================================================================
#  Nomadix - Conference Service (Node.js / Express, port 3000 en interne)
# ============================================================================
FROM node:20-alpine
WORKDIR /app

RUN addgroup -S nomadix && adduser -S nomadix -G nomadix

COPY package.json package-lock.json ./
RUN npm ci --omit=dev

COPY src ./src

USER nomadix
ENV NODE_ENV=production
ENV PORT=3000
EXPOSE 3000

CMD ["node", "src/server.js"]
