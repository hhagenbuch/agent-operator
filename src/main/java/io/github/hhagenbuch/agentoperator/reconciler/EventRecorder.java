package io.github.hhagenbuch.agentoperator.reconciler;

import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectReference;
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Emits Kubernetes Events for reconcile transitions, so {@code kubectl describe}
 * tells the story of a promotion or rollback. The {@link #event} builder is pure
 * (unit-tested); {@link #record} performs the best-effort create — a failed
 * event must never fail a reconcile.
 */
public final class EventRecorder {

    public static final String NORMAL = "Normal";
    public static final String WARNING = "Warning";

    private static final Logger log = LoggerFactory.getLogger(EventRecorder.class);

    private EventRecorder() {
    }

    public static Event event(HasMetadata involved, String type, String reason, String message) {
        ObjectReference ref = new ObjectReferenceBuilder()
                .withApiVersion(involved.getApiVersion())
                .withKind(involved.getKind())
                .withName(involved.getMetadata().getName())
                .withNamespace(involved.getMetadata().getNamespace())
                .withUid(involved.getMetadata().getUid())
                .build();
        return new EventBuilder()
                .withNewMetadata()
                .withGenerateName(involved.getMetadata().getName() + "-")
                .withNamespace(involved.getMetadata().getNamespace())
                .endMetadata()
                .withInvolvedObject(ref)
                .withType(type)
                .withReason(reason)
                .withMessage(message)
                .withReportingComponent("agent-operator")
                .build();
    }

    public static void record(KubernetesClient client, HasMetadata involved,
                              String type, String reason, String message) {
        try {
            client.resource(event(involved, type, reason, message)).create();
        } catch (RuntimeException e) {
            log.warn("Failed to record event {}/{} for {}: {}",
                    type, reason, involved.getMetadata().getName(), e.getMessage());
        }
    }
}
