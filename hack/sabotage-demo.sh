#!/usr/bin/env bash
# The eval-gated-canary demo. Prereqs: a running cluster with the CRDs installed,
# the operator running (hack/demo.sh gets you there), an `Agent` applied, plus:
#   - an agent-evals image loaded as ghcr.io/hhagenbuch/agent-evals:0.1.0
#   - a ConfigMap `support-golden-cases` in namespace `agents` whose `dataset.yaml`
#     key holds English-asserting eval cases
#   - ANTHROPIC_API_KEY present in the `anthropic-key` secret (for judge assertions)
#
# Usage: hack/sabotage-demo.sh
set -euo pipefail
cd "$(dirname "$0")/.."

watch_phase() {  # $1 = promptversion name
  echo "==> watching $1 (Ctrl-C to stop)"
  kubectl -n agents get promptversion "$1" -w -o custom-columns=\
NAME:.metadata.name,PHASE:.status.phase,PASSRATE:.status.evalPassRate &
  local pid=$!
  # stop watching once terminal
  until kubectl -n agents get promptversion "$1" -o jsonpath='{.status.phase}' 2>/dev/null \
        | grep -Eq 'Promoted|RolledBack'; do sleep 2; done
  kill $pid 2>/dev/null || true
  echo
}

echo "### Good prompt → expect Promoted"
kubectl apply -f examples/promptversion-good.yaml
watch_phase support-v2
kubectl -n agents describe promptversion support-v2 | sed -n '/Status:/,$p'

echo
echo "### Sabotaged prompt (answers in French) → expect RolledBack"
kubectl apply -f examples/promptversion-sabotage.yaml
watch_phase support-v3-fr
echo "--- why it rolled back (status.message) ---"
kubectl -n agents get promptversion support-v3-fr -o jsonpath='{.status.message}'; echo
echo
echo "The main Deployment was never touched. kubectl get promptversions:"
kubectl -n agents get promptversions
