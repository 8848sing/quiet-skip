package com.jingjump.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ScanActionBudgetTest {
    @Test
    public void restoresCapacityAfterTheSafetyWindow() {
        ScanActionBudget budget = new ScanActionBudget(12_000L, 3);
        budget.recordAction(1_000L);
        budget.recordAction(2_000L);
        budget.recordAction(3_000L);

        assertFalse(budget.hasCapacity(11_999L));
        assertTrue(budget.hasCapacity(13_000L));
        assertTrue(budget.hasCapacity(13_001L));
    }

    @Test
    public void resetStartsANewImmediatelyUsableWindow() {
        ScanActionBudget budget = new ScanActionBudget(12_000L, 1);
        budget.recordAction(1_000L);
        assertFalse(budget.hasCapacity(1_001L));

        budget.reset();
        assertTrue(budget.hasCapacity(1_002L));
    }
}
