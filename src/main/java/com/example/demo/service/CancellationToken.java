package com.example.demo.service;

import java.util.concurrent.atomic.AtomicBoolean;

public class CancellationToken {

    private final AtomicBoolean cancellationRequested = new AtomicBoolean(false);

    public void cancel() {
        cancellationRequested.set(true);
    }

    public boolean isCancellationRequested() {
        return cancellationRequested.get();
    }
}
