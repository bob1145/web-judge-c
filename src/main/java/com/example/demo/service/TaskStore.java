package com.example.demo.service;

import com.example.demo.dto.JudgeProgress;
import com.example.demo.model.JudgeStatus;
import com.example.demo.model.JudgeTask;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface TaskStore {

    Path taskDirectory(String judgeId);

    JudgeTask create(JudgeTask task) throws IOException;

    Optional<JudgeTask> find(String judgeId) throws IOException;

    List<JudgeTask> findAll() throws IOException;

    JudgeTask updateStatus(String judgeId, JudgeStatus status, String message) throws IOException;

    void saveSummary(String judgeId, JudgeProgress summary) throws IOException;

    Optional<JudgeProgress> findSummary(String judgeId) throws IOException;

    List<JudgeTask> markStaleRunningTasksOnStartup() throws IOException;
}
