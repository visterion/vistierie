package de.vesterion.vistierie.agent.runner;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecursionGuardTest {
    @Test void allowsBelowLimit() {
        var g = new RecursionGuard(3);
        g.enter(); g.enter(); g.enter();
        assertThat(g.depth()).isEqualTo(3);
        g.exit(); g.exit(); g.exit();
    }
    @Test void rejectsAtLimit() {
        var g = new RecursionGuard(2);
        g.enter(); g.enter();
        assertThatThrownBy(g::enter).isInstanceOf(RecursionGuard.DepthExceeded.class);
    }
}
