package com.example.demo.service.sandbox;

import com.example.demo.config.WorkerProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLContext;

public final class TestTlsRemoteWorkerRunner extends RemoteWorkerRunner {

    public TestTlsRemoteWorkerRunner(WorkerProperties properties, ObjectMapper mapper, SSLContext context) {
        super(Options.from(properties), mapper, context);
    }
}
