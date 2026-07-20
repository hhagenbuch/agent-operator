# agent-operator eval-gate demo — reproducible terminal recording.
#
#   brew install vhs && vhs hack/sabotage-demo.tape        (run from the repo root)
#
# Prerequisites — bring the cluster up first with hack/demo.sh, plus the eval
# dataset and the evals image:
#
#   hack/demo.sh                                    # kind cluster, CRDs, operator, Agent
#   kubectl -n agents create configmap support-golden-cases \
#       --from-file=dataset.yaml=examples/golden-cases.yaml
#   kind load docker-image ghcr.io/hhagenbuch/agent-evals:0.1.0 --name agent-operator
#
# WHAT THIS RECORDING DOES AND DOES NOT SHOW
#
# It shows the gate refusing to promote, and the main Deployment surviving that
# refusal untouched. That is the safety property, and it is real: a canary is
# created, a Kubernetes Job runs the eval suite against it, and the operator
# rolls back on a genuine non-zero verdict whose report tail lands in
# status.message.
#
# It does NOT show the gate discriminating between a good and a sabotaged prompt.
# This is the keyless path, so the agent has no model behind it and answers every
# case with a fallback — which means the good prompt would fail this dataset too.
# The full arc (good -> Promoted, sabotaged -> RolledBack) needs a real key and
# runs in CI, where the network is clean and the key is a secret.

Output docs/operator-eval-gate-demo.gif

Set Shell zsh
Set FontSize 15
Set Width 1180
Set Height 660
Set Framerate 10
Set TypingSpeed 12ms
Set Theme "Catppuccin Mocha"
Set Padding 14
Set Margin 8
Set WindowBar Colorful

# ---------------------------------------------------------------- setup (hidden)
Hide
Type `kubectl -n agents delete promptversion support-v3-fr --ignore-not-found >/dev/null 2>&1` Enter
Sleep 5s
Type `clear && printf '\n  \033[1;36magent-operator\033[0m — eval-gated prompt rollout on Kubernetes\n\n  \033[2mkeyless demo: the gate rejecting a prompt, and the main Deployment surviving it\033[0m\n\n'` Enter
Sleep 2s
Show
Sleep 2500ms

# ---------------------------------------------------------------- the demo
# 1. Baseline: the Agent is healthy and serving on support-v1.
Type `kubectl -n agents get agents,deploy` Enter
Sleep 4s

# 2. Roll out a new PromptVersion. The operator canaries it and gates on evals.
Type `kubectl apply -f examples/promptversion-sabotage.yaml` Enter
Sleep 3s

# 3. Watch the state machine walk. Pending and Canary usually pass faster than
#    the first poll, so the recording typically opens on Evaluating -> RolledBack.
Type `for i in $(seq 1 9); do kubectl -n agents get promptversion support-v3-fr \` Enter
Type `  --no-headers -o custom-columns=NAME:.metadata.name,PHASE:.status.phase,PASS:.status.evalPassRate; sleep 2; done` Enter
Sleep 21s

# 4. Why it rolled back — the eval report tail, recorded on the resource itself.
Type `kubectl -n agents get promptversion support-v3-fr -o jsonpath='{.status.message}'` Enter
Sleep 6s

# 5. The point: the main Deployment never moved.
Type `kubectl -n agents get deploy support-agent` Enter
Sleep 5s
