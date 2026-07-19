package io.github.hhagenbuch.agentoperator.reconciler;

import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.github.hhagenbuch.agentoperator.model.PromptVersion;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventRecorderTest {

    private PromptVersion promptVersion() {
        PromptVersion pv = new PromptVersion();
        pv.setMetadata(new ObjectMetaBuilder()
                .withName("support-v3-fr").withNamespace("agents").withUid("uid-123").build());
        return pv;
    }

    @Test
    void buildsAnEventReferencingTheInvolvedResource() {
        Event event = EventRecorder.event(promptVersion(),
                EventRecorder.WARNING, "RolledBack", "eval gate failed");

        assertThat(event.getType()).isEqualTo("Warning");
        assertThat(event.getReason()).isEqualTo("RolledBack");
        assertThat(event.getMessage()).isEqualTo("eval gate failed");
        assertThat(event.getReportingComponent()).isEqualTo("agent-operator");
        assertThat(event.getMetadata().getNamespace()).isEqualTo("agents");
        assertThat(event.getMetadata().getGenerateName()).isEqualTo("support-v3-fr-");

        var involved = event.getInvolvedObject();
        assertThat(involved.getKind()).isEqualTo("PromptVersion");
        assertThat(involved.getName()).isEqualTo("support-v3-fr");
        assertThat(involved.getNamespace()).isEqualTo("agents");
        assertThat(involved.getUid()).isEqualTo("uid-123");
        assertThat(involved.getApiVersion()).isEqualTo("agents.hhagenbuch.io/v1alpha1");
    }
}
