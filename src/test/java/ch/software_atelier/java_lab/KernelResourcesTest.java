package ch.software_atelier.java_lab;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.*;

class KernelResourcesTest {
    @Test void reportsRemoteJvmResourcesEvenWhileACellIsBusyWithoutChangingSessionState() throws Exception {
        try(var kernels=new KernelService()) {
            String id=kernels.create();
            var initial=kernels.resources(id);
            assertThat(initial.available()).isTrue();
            assertThat(initial.pid()).isNotEqualTo(ProcessHandle.current().pid());
            assertThat(initial.heapUsedBytes()).isPositive().isLessThanOrEqualTo(initial.heapCommittedBytes());
            assertThat(initial.heapMaxBytes()).isGreaterThanOrEqualTo(initial.heapCommittedBytes());
            assertThat(kernels.execute(id,"int resourceAnswer = 42;").executionCount()).isEqualTo(1);
            var execution=CompletableFuture.supplyAsync(()->kernels.execute(id,"Thread.sleep(5000);"));
            try {
                Thread.sleep(1500);
                assertThat(execution.isDone()).isFalse();
                var busy=kernels.resources(id);
                assertThat(busy.available()).isTrue();
                assertThat(busy.pid()).isEqualTo(initial.pid());
                assertThat(busy.sampledAt()).isGreaterThan(initial.sampledAt());
                assertThat(busy.cpuPercent()).isNotNull().isGreaterThanOrEqualTo(0);
            } finally { kernels.interrupt(id); execution.get(); }
            var result=kernels.execute(id,"resourceAnswer");
            assertThat(result.executionCount()).isEqualTo(3);
            assertThat(result.outputs().toString()).contains("42");
            kernels.remove(id);
            assertThatThrownBy(()->kernels.resources(id)).isInstanceOf(ResponseStatusException.class);
        }
    }
}
