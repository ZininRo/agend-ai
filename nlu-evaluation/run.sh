#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../backend"
bash ./mvnw -q -DskipTests spring-boot:run \
  -Dspring-boot.run.main-class=com.example.agend_ai.evaluation.NluEvaluationCli \
  -Dspring-boot.run.arguments="$*"
