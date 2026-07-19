# agent-operator

> Prompts and model versions change agent behavior as much as code changes
> service behavior — but most teams deploy them with a config edit and a
> prayer. `agent-operator` makes agent changes first-class Kubernetes
> deployments: declare an `Agent`, roll out a `PromptVersion` as a canary,
> gate promotion on an eval suite run in-cluster, and roll back automatically
> on regression. Prompts have SLOs now.

**Status: Phase 0 — design.** This repo currently contains the design only.
See [`docs/DESIGN.md`](docs/DESIGN.md) for the RFC (CRDs, state machine,
reconcile sequence). Code lands in phases; roadmap below.

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

- [ ] Phase 0 — design doc (this)
- [ ] Phase 1 — `Agent` controller (Deployment/Service/ConfigMap reconcile) on `kind`
- [ ] Phase 2 — `PromptVersion` controller + in-cluster eval-gated canary + auto-rollback
- [ ] Phase 3 — printer columns, Events, Helm/kustomize install, quickstart, GIF
- [ ] Later — traffic-weighted canary (Gateway API), drift detection (nightly re-eval), `ModelVersion` CRD

## Quickstart (target)

`kind create cluster` → install the operator → `kubectl apply` an `Agent` →
curl its chat endpoint, all in under five minutes. See `docs/DESIGN.md` until
Phase 1 lands.

## License

MIT — see [LICENSE](LICENSE).
