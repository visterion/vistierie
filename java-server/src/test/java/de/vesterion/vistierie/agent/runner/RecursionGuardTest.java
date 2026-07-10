package de.vesterion.vistierie.agent.runner;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecursionGuardTest {

    @Test void allowsDepthUpToAndIncludingMax() {
        var g = new RecursionGuard(2);
        assertThatCode(() -> g.check(0)).doesNotThrowAnyException();
        assertThatCode(() -> g.check(1)).doesNotThrowAnyException();
        assertThatCode(() -> g.check(2)).doesNotThrowAnyException();
    }

    @Test void rejectsDepthAboveMax() {
        var g = new RecursionGuard(2);
        assertThatThrownBy(() -> g.check(3)).isInstanceOf(RecursionGuard.DepthExceeded.class);
    }

    @Test void isStatelessAcrossCalls() {
        // No per-call state: repeated over-limit checks keep throwing, and an under-limit
        // check after an over-limit one still passes.
        var g = new RecursionGuard(1);
        assertThatThrownBy(() -> g.check(2)).isInstanceOf(RecursionGuard.DepthExceeded.class);
        assertThatCode(() -> g.check(1)).doesNotThrowAnyException();
        assertThatThrownBy(() -> g.check(2)).isInstanceOf(RecursionGuard.DepthExceeded.class);
    }
}
