#!/usr/bin/env bash
# Regenerate the CRDs from the Java model and copy them into deploy/crds/.
# Run this whenever the Agent/PromptVersion model changes.
set -euo pipefail
cd "$(dirname "$0")/.."

mvn -q -DskipTests compile
cp target/classes/META-INF/fabric8/agents.agents.hhagenbuch.io-v1.yml deploy/crds/agent-crd.yaml
cp target/classes/META-INF/fabric8/promptversions.agents.hhagenbuch.io-v1.yml deploy/crds/promptversion-crd.yaml
echo "synced deploy/crds/ from the generated CRDs"
