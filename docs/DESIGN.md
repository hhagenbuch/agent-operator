# agent-operator — Design (RFC)

**Status:** Draft / pre-code (Phase 0)
**Author:** Heyward Hagenbuch

A Kubernetes operator that makes prompt and model changes first-class,
eval-gated deployments: canary → in-cluster eval → promote or auto-rollback.

## 1. Problem

A prompt edit or a model bump changes agent behavior as much as a code change
changes service behavior — but teams ship them with a ConfigMap edit and no
gate. There is no canary, no automated quality check, no rollback story. This
operator gives prompts the same deployment discipline code already has, using
Kubernetes primitives rather than reinventing them.

## 2. CRDs (`agents.hhagenbuch.io/v1alpha1`)

### 2.1 `Agent` — the long-running workload

```yaml
apiVersion: agents.hhagenbuch.io/v1alpha1
kind: Agent
metadata:
  name: support-agent
spec:
  image: ghcr.io/hhagenbuch/spring-ai-agent-starter:0.1.0
  replicas: 2
  model: claude-sonnet-5
  apiKeySecretRef: { name: anthropic-key, key: api-key }
  activePromptVersion: support-v3          # managed by the operator, not humans
  evalGate:
    datasetConfigMap: support-golden-cases  # agent-evals YAML
    minPassRate: "0.9"
```

### 2.2 `PromptVersion` — an immutable, versioned change

```yaml
apiVersion: agents.hhagenbuch.io/v1alpha1
kind: PromptVersion
metadata:
  name: support-v4
spec:
  agentRef: support-agent
  systemPrompt: |
    You are a support agent...
  model: claude-sonnet-5        # optional override — model bumps flow through the same gate
  rollout:
    strategy: EvalGatedCanary   # or Immediate (dev only)
    canaryWeight: 20            # % traffic during canary (stretch; MVP = eval-only gate)
```

### 2.3 Status is the product

`PromptVersion.status.phase`: `Pending → Canary → Evaluating → Promoted |
RolledBack`. Conditions carry the eval summary (`evalPassRate: 0.94`, a link to
the report). `kubectl get promptversions` showing *why* a prompt was rolled
back is the demo money-shot.

## 3. Reconcile loops

### 3.1 Agent controller
Ensures a Deployment + Service + ConfigMap exist and match spec. Prompt content
lives in a ConfigMap named `{agent}-{promptversion}`; **the pod template hashes
that ConfigMap**, so a promotion is an ordinary rolling update — we inherit
k8s rollout/rollback semantics for free instead of reinventing them. This is a
load-bearing design decision, not an implementation detail.

### 3.2 PromptVersion controller (the interesting one)

1. New `PromptVersion` → create a **canary Deployment** (1 replica) of the agent
   image with the new prompt ConfigMap, **not** wired to the Service.
2. Launch an **eval Job**: the `agent-evals` shaded jar, dataset mounted from
   `evalGate.datasetConfigMap`, `--target http://{canary-service}/api/chat`,
   `--min-pass-rate` from the gate. (This is why the starter suite's
   `--min-pass-rate` flag had to exist first.)
3. Job exit 0 → **Promote**: patch `Agent.spec.activePromptVersion`, let the
   Agent controller roll the main Deployment, delete the canary; phase
   `Promoted`.
4. Job exit 1 → **RolledBack**: delete the canary, leave the main Deployment
   untouched, pull the eval report tail from the Job pod logs into a status
   condition + a k8s Event; phase `RolledBack`.
5. Emit **Events** for every transition — `kubectl describe` should tell the
   whole story.

Judge assertions need the API key; the eval Job mounts `Agent.spec.apiKeySecretRef`.
Without a key, the deterministic assertion tier still gates (documented behavior
inherited from `agent-evals`).

### 3.3 State machine

```
Pending ──► Canary ──► Evaluating ──► Promoted
                            │
                            └────────► RolledBack
```

## 4. Design decisions

- **ConfigMap-hash promotion.** Promotion = pod-template hash change = standard
  rolling update. Rollback is a k8s primitive, not a revert PR.
- **Job-based eval gate.** The gate runs *in-cluster against the real running
  canary*, not in external CI against a mock. That is the difference from "just
  CI."
- **Java, not Go.** Java Operator SDK (`io.javaoperatorsdk:operator-framework`,
  4.x/5.x) handles informers/requeue. Keeps the portfolio on one stack and is
  itself a talking point in a Go-default ecosystem.
- **CRDs from Java sources.** Fabric8 `crd-generator-apt` emits CRD YAML from
  the Java model classes — single source of truth.
- **Validation in the reconciler**, not admission webhooks (see non-goals).

## 5. Non-goals (MVP)

- Traffic-split / weighted canary (Gateway API) — `canaryWeight` is parsed but
  the MVP gate is eval-only, no live traffic split.
- Multi-cluster.
- Admission/validation webhooks + cert-manager.
- HPA / autoscaling.

## 6. Tech + local target

- **Java Operator SDK** on Java 21 + Maven.
- **Fabric8** CRD generator.
- Local cluster: **kind**. Everything must run on a laptop from
  `kind create cluster`. The starter image is built with
  `mvn spring-boot:build-image` and `kind load docker-image`, scripted under
  `hack/`.

## 7. Build phases

0. **Design** — this document, committed alone.
1. **Agent controller** — `Agent` CRD + reconcile Deployment/Service/ConfigMap;
   unit tests with JOSDK mock-server support; `kind` e2e applies an Agent and
   curls the chat endpoint.
2. **PromptVersion controller + eval gate** — the state machine above. e2e:
   a good `PromptVersion` → `Promoted`; a sabotaged one ("always answer in
   French") against an English-asserting dataset → `RolledBack` with the failing
   report in `kubectl describe`. That sabotage demo is the GIF.
3. **Polish** — printer columns (PHASE, PASSRATE, AGE), Events everywhere,
   Helm/kustomize install, README quickstart + architecture diagram.

## 8. Guardrails

Clean room. No employer names or domains anywhere. No reuse of internal
manifests or operator code — this is built from the public k8s + JOSDK APIs
only.
