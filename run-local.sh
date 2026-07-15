#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${ROOT_DIR}/.env.local"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "Missing ${ENV_FILE}."
  echo "Create it from ${ROOT_DIR}/.env.local.example and set SPRING_MONGODB_URI."
  exit 1
fi

SPRING_MONGODB_URI="$(grep -E '^SPRING_MONGODB_URI=' "${ENV_FILE}" | head -n 1 | cut -d '=' -f 2-)"
echo "SPRING_MONGODB_URI=${SPRING_MONGODB_URI}"
if [[ -z "${SPRING_MONGODB_URI}" ]]; then
  echo "SPRING_MONGODB_URI is not set in ${ENV_FILE}."
  exit 1
fi

export SPRING_MONGODB_URI

exec "${ROOT_DIR}/mvnw" "$@"
