package io.github.hhagenbuch.agentoperator;

import io.github.hhagenbuch.agentoperator.reconciler.AgentReconciler;
import io.javaoperatorsdk.operator.Operator;

/**
 * Entry point: registers the {@link AgentReconciler} and starts the operator.
 * Uses the ambient kube context (in-cluster service account, or your local
 * kubeconfig when run against {@code kind}).
 */
public final class OperatorMain {

    private OperatorMain() {
    }

    public static void main(String[] args) {
        Operator operator = new Operator();
        operator.register(new AgentReconciler());
        operator.start();
        Runtime.getRuntime().addShutdownHook(new Thread(operator::stop));
    }
}
