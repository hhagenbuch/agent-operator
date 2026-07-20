#!/usr/bin/env bash
# End-to-end local demo on kind. Requires: kind, kubectl, docker, mvn, java 25,
# and a sibling checkout of spring-ai-agent-starter at ../spring-ai-agent-starter.
#
# Usage: hack/demo.sh
set -euo pipefail
cd "$(dirname "$0")/.."

CLUSTER=agent-operator
STARTER_DIR=../spring-ai-agent-starter
STARTER_IMAGE=ghcr.io/hhagenbuch/spring-ai-agent-starter:0.1.0

echo "==> kind cluster"
kind get clusters | grep -qx "$CLUSTER" || kind create cluster --name "$CLUSTER"

echo "==> build + generate CRD"
mvn -q -DskipTests package
CRD=target/classes/META-INF/fabric8/agents.agents.hhagenbuch.io-v1.yml

echo "==> build the agent image and load it into kind"
if [ -d "$STARTER_DIR" ]; then
  ( cd "$STARTER_DIR" && mvn -q -DskipTests spring-boot:build-image \
      -Dspring-boot.build-image.imageName="$STARTER_IMAGE" )
  kind load docker-image "$STARTER_IMAGE" --name "$CLUSTER"
else
  echo "    (skipped: $STARTER_DIR not found — the Deployment will ImagePullBackOff without the image)"
fi

echo "==> install CRD"
kubectl apply -f "$CRD"

echo "==> run the operator (background) against this cluster"
java -jar target/agent-operator-0.1.0-SNAPSHOT.jar &
OPERATOR_PID=$!
trap 'kill $OPERATOR_PID 2>/dev/null || true' EXIT
sleep 3

echo "==> apply the example Agent"
kubectl create namespace agents --dry-run=client -o yaml | kubectl apply -f -
kubectl -n agents create secret generic anthropic-key \
  --from-literal=api-key="${ANTHROPIC_API_KEY:-sk-ant-placeholder}" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -f examples/agent.yaml

echo "==> what the operator created"
sleep 3
kubectl -n agents get agents,deploy,svc,cm
echo
echo "Edit examples/agent.yaml's systemPrompt and re-apply to watch the Deployment roll."
