#!/usr/bin/env bash
# WP8: build the production gateway image with Jib (no Dockerfile, no daemon
# needed for the build itself) and optionally tag/push it to the private
# registry. Requires JDK 26 (same as ./mvnw verify).
#
#   ./build-image.sh                 # local image hc-professional-gateway:latest
#   ./build-image.sh 1.0.0           # + version tag
#   PUSH=1 ./build-image.sh 1.0.0    # + tag and push to $REGISTRY
set -euo pipefail

IMAGE=hc-professional-gateway
VERSION="${1:-latest}"
REGISTRY="${REGISTRY:-docker-registry.jojoaddison.net}"

if [ -d /usr/lib/jvm/jdk-26-oracle-x64 ] && [ -z "${JAVA_HOME:-}" ]; then
  export JAVA_HOME=/usr/lib/jvm/jdk-26-oracle-x64
fi

./mvnw -Pprod -DskipTests clean package jib:dockerBuild -Djib.to.image="$IMAGE:$VERSION"

if [ "$VERSION" != "latest" ]; then
  docker tag "$IMAGE:$VERSION" "$IMAGE:latest"
fi

if [ "${PUSH:-0}" = "1" ]; then
  docker tag "$IMAGE:$VERSION" "$REGISTRY/$IMAGE:$VERSION"
  docker tag "$IMAGE:$VERSION" "$REGISTRY/$IMAGE:latest"
  docker push "$REGISTRY/$IMAGE:$VERSION"
  docker push "$REGISTRY/$IMAGE:latest"
fi

echo "Built $IMAGE:$VERSION"
