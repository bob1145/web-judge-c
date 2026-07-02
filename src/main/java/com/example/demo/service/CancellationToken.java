package com.example.demo.service;

import java.util.concurrent.atomic.AtomicReference;

public class CancellationToken {

    private final AtomicReference<Reason> reason = new AtomicReference<>(Reason.NONE);

    public void cancel() {
        reason.compareAndSet(Reason.NONE, Reason.USER_REQUESTED);
    }

    public boolean cancelForBudgetExceeded() {
        return reason.compareAndSet(Reason.NONE, Reason.BUDGET_EXCEEDED);
    }

    public boolean isCancellationRequested() {
        return reason.get() != Reason.NONE;
    }

    public boolean isBudgetExceeded() {
        return reason.get() == Reason.BUDGET_EXCEEDED;
    }

    public Reason reason() {
        return reason.get();
    }

    public enum Reason {
        NONE,
        USER_REQUESTED,
        BUDGET_EXCEEDED
    }
}
