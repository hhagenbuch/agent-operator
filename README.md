# agent-operator

> Prompts and model versions change agent behavior as much as code changes
> service behavior — but most teams deploy them with a config edit and a
> prayer. `agent-operator` makes agent changes first-class Kubernetes
> deployments: declare an `Agent`, roll out a `PromptVersion` as a canary,
> gate promotion on an eval suite run in-cluster, and roll back automatically
> on regression. Prompts have SLOs now.

[![CI](https://github.com/hhagenbuch/agent-operator/actions/workflows/ci.yml/badge.svg)](https://github.com/hhagenbuch/agent-operator/actions/workflows/ci.yml)

**Status: Phase 2 — eval-gated canary.** The operator reconciles an `Agent` into
a Deployment + Service + ConfigMap, and rolls out a `PromptVersion` through a
canary + in-cluster eval Job: `Pending → Canary → Evaluating → Promoted |
RolledBack`. A passing gate promotes (patching the Agent's active prompt); a
failing gate rolls back untouched and records why. Both CRDs are generated from
the Java model at build time. See [`docs/DESIGN.md`](docs/DESIGN.md) for the RFC.

## The idea

GitOps for prompts. Two CRDs in `agents.hhagenbuch.io/v1alpha1`:

- **`Agent`** — the long-running workload (image, replicas, model, the active
  prompt version, and an eval gate). The operator owns `activePromptVersion`,
  not humans.
- **`PromptVersion`** — an immutable, versioned prompt/model change. Applying
  one spins up a **canary**, runs an **eval Job** in-cluster against it, and
  promotes or rolls back on the result.

`PromptVersion.status.phase` walks `Pending → Canary → Evaluating → Promoted |
RolledBack`, with the eval report summary in its conditions. `kubectl get
promptversions` telling you *why* a prompt rolled back is the point.

```
apply PromptVersion ─► canary Deployment (1 replica, no Service traffic)
                          │
                          ▼
                     eval Job: agent-evals jar --target canary --min-pass-rate
                          │
              exit 0 ─────┴───── exit 1
                 │                  │
             Promote            RolledBack
   (patch Agent.activePromptVersion,   (delete canary, main untouched,
    roll main Deployment, del canary)   attach eval report to status + Events)
```

## Why a Java operator

The operator ecosystem defaults to Go; a Java operator (via the Java Operator
SDK) is a differentiator and keeps the whole portfolio on one stack. It
*deploys* [spring-ai-agent-starter](https://github.com/hhagenbuch/spring-ai-agent-starter)
and *gates* with [agent-evals](https://github.com/hhagenbuch/agent-evals) — the
three repos closing into a platform is the point.

## Roadmap

- [x] Phase 0 — design doc
- [x] Phase 1 — `Agent` controller (Deployment/Service/ConfigMap reconcile) on `kind`
- [x] Phase 2 — `PromptVersion` controller + in-cluster eval-gated canary + auto-rollback
- [x] Phase 3 — printer columns, Events, kustomize install, quickstart
- [ ] Later — traffic-weighted canary (Gateway API), drift detection (nightly re-eval), `ModelVersion` CRD

## Build

```bash
mvn verify
```

Java 21 + Maven. The build compiles the operator, runs the reconcile tests, and
generates the `Agent` CRD from the Java model via `crd-generator-apt` (into
`target/classes/META-INF/fabric8/`).

## Quickstart (kind)

With `kind`, `kubectl`, and Docker, and a sibling checkout of
[spring-ai-agent-starter](https://github.com/hhagenbuch/spring-ai-agent-starter)
at `../spring-ai-agent-starter`:

```bash
hack/demo.sh
```

It spins up a `kind` cluster, builds + loads the agent image, installs the CRD,
runs the operator, applies [`examples/agent.yaml`](examples/agent.yaml), and
shows the Deployment/Service/ConfigMap the operator created. Edit the Agent's
`systemPrompt` and re-apply to watch the Deployment roll.

## Install in-cluster (kustomize)

CRDs + RBAC + the operator Deployment, in one apply:

```bash
docker build -t ghcr.io/hhagenbuch/agent-operator:0.1.0 .
kind load docker-image ghcr.io/hhagenbuch/agent-operator:0.1.0   # for a kind cluster
kubectl apply -k deploy
```

The CRDs under `deploy/crds/` are generated from the Java model — regenerate
them after a model change with `hack/sync-crds.sh`.

Run it locally instead (uses your kubeconfig):

```bash
mvn -DskipTests package
kubectl apply -k deploy/crds
java -jar target/agent-operator-0.1.0-SNAPSHOT.jar
```

## Observability

Every transition emits a Kubernetes **Event**, so `kubectl describe promptversion
<name>` reads like a changelog (`CanaryCreated` → `EvalStarted` → `Promoted` or
`RolledBack`), and printer columns surface the state at a glance:

```console
$ kubectl -n agents get agents
NAME            MODEL             ACTIVE
support-agent   claude-sonnet-5   support-v2
```

## Eval-gated rollout (the demo)

With the operator running and an `Agent` applied (plus an `agent-evals` image and
an English-asserting dataset ConfigMap):

```bash
hack/sabotage-demo.sh
```

- A **good** `PromptVersion` passes the in-cluster eval Job and is **Promoted** —
  the operator patches `Agent.spec.activePromptVersion`, rolling the main
  Deployment.
- A **sabotaged** one (`systemPrompt: "always answer in French"`) **fails** the
  gate against the English dataset and is **RolledBack**, leaving the main
  Deployment untouched and recording why:

```console
$ kubectl -n agents get promptversions
NAME            PHASE        PASSRATE
support-v2      Promoted     pass
support-v3-fr   RolledBack   fail
```

The promotion mechanism is deliberately boring: the canary is a 1-replica
Deployment off the main Service; the gate is a Kubernetes Job running the
`agent-evals` jar with `--target` the canary and `--min-pass-rate` from the
Agent's `evalGate`. Exit 0 promotes, exit 1 rolls back — CI-style gating, but
in-cluster against the real runtime.

> The state machine (`Pending → Canary → Evaluating → Promoted | RolledBack`)
> lives as a pure function in `PromotionStateMachine` and is exhaustively unit
> tested; the reconciler only performs the cluster effects it returns.

## License

MIT — see [LICENSE](LICENSE).
