# agent-operator

> Prompts and model versions change agent behavior as much as code changes
> service behavior — but most teams deploy them with a config edit and a
> prayer. `agent-operator` makes agent changes first-class Kubernetes
> deployments: declare an `Agent`, roll out a `PromptVersion` as a canary,
> gate promotion on an eval suite run in-cluster, and roll back automatically
> on regression. Prompts have SLOs now.

[![CI](https://github.com/hhagenbuch/agent-operator/actions/workflows/ci.yml/badge.svg)](https://github.com/hhagenbuch/agent-operator/actions/workflows/ci.yml)

**Status: Phase 1 — the `Agent` controller.** The operator reconciles an
`Agent` into a Deployment + Service + ConfigMap; a prompt change rolls the
Deployment via a pod-template hash. The `Agent` CRD is generated from the Java
model at build time. See [`docs/DESIGN.md`](docs/DESIGN.md) for the full RFC;
roadmap below.

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
- [ ] Phase 2 — `PromptVersion` controller + in-cluster eval-gated canary + auto-rollback
- [ ] Phase 3 — printer columns, Events, Helm/kustomize install, quickstart, GIF
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

Manual install (operator + CRD only):

```bash
mvn -DskipTests package
kubectl apply -f target/classes/META-INF/fabric8/agents.agents.hhagenbuch.io-v1.yml
java -jar target/agent-operator-0.1.0-SNAPSHOT.jar   # uses your kubeconfig
```

## License

MIT — see [LICENSE](LICENSE).
