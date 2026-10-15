package com.example.demo.service;

import com.example.demo.config.ExecutionProperties;
import com.example.demo.dto.JudgeProgress;
import com.example.demo.dto.JudgeProgressEvent;
import com.example.demo.dto.TestCaseDetail;
import com.example.demo.dto.TestCaseResult;
import com.example.demo.model.JudgeTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
public class JudgeFileService {

    private static final Pattern SAFE_ENTRY_NAME = Pattern.compile("[1-9][0-9]*\\.(in|out|ans)");

    private final TaskStore taskStore;
    private final ExecutionProperties executionProperties;

    public TestCaseDetail getTestCaseDetails(String judgeId, int caseNumber) throws IOException {
        JudgeTask task = requireTask(judgeId);
        validateCaseNumber(task, caseNumber);
        Path workDir = requireWorkDir(task);

        Path inputFile = caseFile(workDir, caseNumber, ".in");
        Path userOutputFile = caseFile(workDir, caseNumber, ".out");
        Path correctOutputFile = caseFile(workDir, caseNumber, ".ans");
        long maxBytes = detailLimit(task);
        FilePreview input = readRequiredUtf8(inputFile, maxBytes, "input");
        FilePreview userOutput = readOptionalUtf8(userOutputFile, maxBytes);
        FilePreview correctOutput = readOptionalUtf8(correctOutputFile, maxBytes);

        return new TestCaseDetail(
                input.content(),
                userOutput.content(),
                correctOutput.content(),
                input.truncated(),
                userOutput.truncated(),
                correctOutput.truncated()
        );
    }

    public Path getTestCaseInputFile(String judgeId, int caseNumber) throws IOException {
        JudgeTask task = requireTask(judgeId);
        validateCaseNumber(task, caseNumber);
        Path inputFile = caseFile(requireWorkDir(task), caseNumber, ".in");
        if (!Files.isRegularFile(inputFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Input file not found for caseNumber " + caseNumber);
        }
        return inputFile;
    }

    public StreamingResponseBody streamAllTestCasesArchive(String judgeId) throws IOException {
        JudgeTask task = requireTask(judgeId);
        if (task.getPolicy() != null && task.getPolicy().highVolume()) {
            throw new IOException("Full archive download is disabled for high-volume tasks; use per-case downloads");
        }
        Path workDir = requireWorkDir(task);
        JudgeProgress progress = taskStore.findSummary(judgeId)
                .orElseThrow(() -> new IOException("No completed test case results available"));
        List<Integer> caseNumbers = progress.getResults() == null ? List.of() : progress.getResults().stream()
                .map(TestCaseResult::getCaseNumber)
                .distinct()
                .sorted()
                .toList();
        if (caseNumbers.isEmpty()) {
            throw new IOException("No completed test case results available");
        }
        return streamCaseArtifacts(task, workDir, caseNumbers);
    }

    public StreamingResponseBody streamTestCaseArchive(String judgeId, int caseNumber) throws IOException {
        JudgeTask task = requireTask(judgeId);
        return streamCaseArtifacts(task, requireWorkDir(task), List.of(caseNumber));
    }

    public StreamingResponseBody streamFailedTestCasesArchive(String judgeId) throws IOException {
        JudgeTask task = requireTask(judgeId);
        Path workDir = requireWorkDir(task);
        List<Integer> failedCases = failedCaseNumbers(judgeId);
        if (failedCases.isEmpty()) {
            throw new IOException("No non-AC test cases available");
        }
        return streamCaseArtifacts(task, workDir, failedCases);
    }

    private StreamingResponseBody streamCaseArtifacts(JudgeTask task, Path workDir, List<Integer> caseNumbers) throws IOException {
        for (int caseNumber : caseNumbers) {
            validateCaseNumber(task, caseNumber);
            Path inputFile = caseFile(workDir, caseNumber, ".in");
            if (!Files.isRegularFile(inputFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Input file not found for caseNumber " + caseNumber);
            }
            caseFile(workDir, caseNumber, ".out");
            caseFile(workDir, caseNumber, ".ans");
        }
        return outputStream -> {
            try (ZipOutputStream zipStream = new ZipOutputStream(outputStream)) {
                for (int caseNumber : caseNumbers) {
                    addFileToZip(zipStream, caseFile(workDir, caseNumber, ".in"), caseNumber + ".in");
                    addOptionalFileToZip(zipStream, caseFile(workDir, caseNumber, ".out"), caseNumber + ".out");
                    addOptionalFileToZip(zipStream, caseFile(workDir, caseNumber, ".ans"), caseNumber + ".ans");
                }
            }
        };
    }

    public String archiveFilename(String judgeId) {
        String safeJudgeId = judgeId == null ? "unknown" : judgeId.replaceAll("[^A-Za-z0-9._-]", "_");
        return "testcases-" + safeJudgeId + ".zip";
    }

    public String failedArchiveFilename(String judgeId) {
        String safeJudgeId = judgeId == null ? "unknown" : judgeId.replaceAll("[^A-Za-z0-9._-]", "_");
        return "failed-testcases-" + safeJudgeId + ".zip";
    }

    private JudgeTask requireTask(String judgeId) throws IOException {
        try {
            return taskStore.find(judgeId)
                    .orElseThrow(() -> new IOException("Judge task not found"));
        } catch (IllegalArgumentException e) {
            throw new IOException("Judge task not found", e);
        }
    }

    private Path requireWorkDir(JudgeTask task) throws IOException {
        Path workDir = Path.of(task.getWorkDir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(workDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Judge task files not found");
        }
        if (taskStore instanceof FileTaskStore fileTaskStore) {
            Path storageBase = fileTaskStore.storageBase().toAbsolutePath().normalize();
            if (!workDir.startsWith(storageBase)) {
                throw new IOException("Judge task files not found");
            }
            Path realStorageBase = storageBase.toRealPath();
            Path realWorkDir = workDir.toRealPath();
            if (!realWorkDir.startsWith(realStorageBase)) {
                throw new IOException("Judge task files not found");
            }
        }
        return workDir;
    }

    private void validateCaseNumber(JudgeTask task, int caseNumber) throws IOException {
        if (caseNumber < 1 || caseNumber > task.getRequestedCases()) {
            throw new IOException("Invalid caseNumber");
        }
    }

    private Path caseFile(Path workDir, int caseNumber, String extension) throws IOException {
        Path file = workDir.resolve(caseNumber + extension).toAbsolutePath().normalize();
        if (!file.startsWith(workDir)) {
            throw new IOException("Resolved case file is outside task directory");
        }
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Resolved case file is not a regular file");
            }
            Path realWorkDir = workDir.toRealPath();
            Path realFile = file.toRealPath();
            if (!realFile.startsWith(realWorkDir)) {
                throw new IOException("Resolved case file is outside task directory");
            }
        }
        return file;
    }

    private FilePreview readRequiredUtf8(Path file, long maxBytes, String label) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " file not found");
        }
        return readUtf8Preview(file, maxBytes);
    }

    private FilePreview readOptionalUtf8(Path file, long maxBytes) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return new FilePreview("", false);
        }
        return readUtf8Preview(file, maxBytes);
    }

    private long detailLimit(JudgeTask task) throws IOException {
        long configuredLimit = executionProperties.getMaxDetailPreviewBytes();
        if (configuredLimit <= 0) {
            throw new IOException("maxDetailPreviewBytes must be positive");
        }
        if (task.getPolicy() == null || task.getPolicy().maxOutputBytesPerCase() <= 0) {
            return configuredLimit;
        }
        return Math.min(configuredLimit, task.getPolicy().maxOutputBytesPerCase());
    }

    private FilePreview readUtf8Preview(Path file, long maxBytes) throws IOException {
        long safeLimit = Math.max(0, maxBytes);
        long size = Files.size(file);
        int bytesToRead = (int) Math.min(size, safeLimit);
        byte[] bytes;
        try (InputStream inputStream = Files.newInputStream(file)) {
            bytes = inputStream.readNBytes(bytesToRead);
        }
        return new FilePreview(new String(bytes, StandardCharsets.UTF_8), size > safeLimit);
    }


    private void addFileToZip(ZipOutputStream zipStream, Path file, String entryName) throws IOException {
        if (!SAFE_ENTRY_NAME.matcher(entryName).matches()) {
            throw new IOException("Unsafe zip entry name");
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Archive source file not found");
        }
        zipStream.putNextEntry(new ZipEntry(entryName));
        try (InputStream inputStream = Files.newInputStream(file)) {
            inputStream.transferTo(zipStream);
        }
        zipStream.closeEntry();
    }

    private void addOptionalFileToZip(ZipOutputStream zipStream, Path file, String entryName) throws IOException {
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            addFileToZip(zipStream, file, entryName);
        }
    }

    private List<Integer> failedCaseNumbers(String judgeId) throws IOException {
        JudgeProgress progress = taskStore.findSummary(judgeId)
                .orElseThrow(() -> new IOException("Judge result summary not found"));
        Set<Integer> caseNumbers = new LinkedHashSet<>();
        if (progress.getResults() != null) {
            progress.getResults().stream()
                    .filter(result -> !isAccepted(result.getStatus()))
                    .map(TestCaseResult::getCaseNumber)
                    .forEach(caseNumbers::add);
        }
        if (caseNumbers.isEmpty()
                && progress.getSummary() != null
                && progress.getSummary().getFailureSamples() != null) {
            progress.getSummary().getFailureSamples().stream()
                    .filter(event -> event != null && !isAccepted(event.getStatus()))
                    .map(JudgeProgressEvent::getCaseNumber)
                    .forEach(caseNumbers::add);
        }
        if (caseNumbers.isEmpty()
                && progress.getSummary() != null
                && progress.getSummary().getFirstFailedCase() != null) {
            caseNumbers.add(progress.getSummary().getFirstFailedCase());
        }
        return new ArrayList<>(caseNumbers);
    }

    private boolean isAccepted(String status) {
        return "AC".equals(normalizeStatus(status));
    }

    private String normalizeStatus(String status) {
        if (status == null) {
            return "";
        }
        return status.trim()
                .toUpperCase()
                .replace(' ', '_')
                .replace('-', '_');
    }


    private record FilePreview(String content, boolean truncated) {
    }
}
