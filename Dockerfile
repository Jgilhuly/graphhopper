# Multi-stage build for GraphHopper on Railway
# Stage 1: Build with Maven and Node.js
FROM eclipse-temurin:17-jdk-alpine AS builder

# Install build dependencies
RUN apk add --no-cache \
    maven \
    nodejs \
    npm \
    wget

WORKDIR /build

# Copy Maven configuration files
COPY pom.xml ./
COPY core/pom.xml ./core/
COPY web/pom.xml ./web/
COPY web-api/pom.xml ./web-api/
COPY web-bundle/pom.xml ./web-bundle/
COPY reader-gtfs/pom.xml ./reader-gtfs/
COPY map-matching/pom.xml ./map-matching/
COPY navigation/pom.xml ./navigation/
COPY client-hc/pom.xml ./client-hc/
COPY tools/pom.xml ./tools/
COPY example/pom.xml ./example/

# Download dependencies (cached layer)
RUN mvn dependency:go-offline -B || true

# Copy source code
COPY . .

# Build the JAR (skip tests for faster build)
RUN mvn clean package -DskipTests -B

# Download Berlin map data
RUN wget -q http://download.geofabrik.de/europe/germany/berlin-latest.osm.pbf -O berlin-latest.osm.pbf

# Stage 2: Runtime with JRE only
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Install runtime dependencies
RUN apk add --no-cache \
    curl

# Copy built JAR from builder stage
COPY --from=builder /build/web/target/graphhopper-web-*.jar ./graphhopper-web.jar

# Copy Berlin map data
COPY --from=builder /build/berlin-latest.osm.pbf ./berlin-latest.osm.pbf

# Copy config file
COPY config-railway.yml ./config.yml

# Create logs directory
RUN mkdir -p logs

# Expose port (Railway will override via PORT env var)
EXPOSE 8989

# Railway provides PORT environment variable
# Use it to override Dropwizard's default port
# Bind to 0.0.0.0 to accept Railway's proxy connections
# Set memory limits appropriate for Railway (typically 512MB-2GB available)
ENV JAVA_OPTS="-Xmx1500m -Xms1500m -XX:+UseParallelGC"

CMD java $JAVA_OPTS \
  -Ddw.graphhopper.datareader.file=berlin-latest.osm.pbf \
  -Ddw.server.application_connectors[0].port=${PORT:-8989} \
  -Ddw.server.application_connectors[0].bind_host=0.0.0.0 \
  -jar graphhopper-web.jar server config.yml

