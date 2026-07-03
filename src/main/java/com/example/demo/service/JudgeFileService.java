package com.example.demo.service;

import com.example.demo.dto.TestCaseDetail;
import com.example.demo.model.JudgeTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
public class JudgeFileService {

    private static final long DEFAULT_DETAIL_LIMIT_BYTES = 1024L * 1024;
    private static final Pattern SAFE_ENTRY_NAME = Pattern.compile("[1-9][0-9]*\\.(in|out)");
    private static final Pattern INPUT_FILE_NAME = Pattern.compile("[1-9][0-9]*\\.in");

    private final TaskStore taskStore;

    public TestCaseDetail getTestCaseDetails(String judgeId, int caseNumber) throws IOException {
        JudgeTask task = requireTask(judgeId);
        validateCaseNumber(task, caseNumber);
        Path workDir = requireWorkDir(task);

        Path inputFile = caseFile(workDir, caseNumber, ".in");
        Path userOutputFile = caseFile(workDir, caseNumber, ".out");
        Path correctOutputFile = caseFile(workDir, caseNumber, ".ans");
        long maxBytes = detailLimit(task);

        return new TestCaseDetail(
                readRequiredUtf8(inputFile, maxBytes, "input"),
                readRequiredUtf8(userOutputFile, maxBytes, "user output"),
                readRequiredUtf8(correctOutputFile, maxBytes, "correct output")
        );
    }

    public Path getTestCaseInputFile(String judgeId, int caseNumber) throws IOException {
        JudgeTask task = requireTask(judgeId);
        validateCaseNumber(task, caseNumber);
        Path inputFile = caseFile(requireWorkDir(task), caseNumber, ".in");
        if (!Files.isRegularFile(inputFile)) {
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
        List<Path> inputFiles = listInputFiles(task, workDir);
        if (inputFiles.isEmpty()) {
            throw new IOException("No test case inputs available");
        }

        return outputStream -> {
            try (ZipOutputStream zipStream = new ZipOutputStream(outputStream)) {
                for (Path inputFile : inputFiles) {
                    int caseNumber = parseCaseNumber(inputFile);
                    addFileToZip(zipStream, inputFile, caseNumber + ".in");

                    Path answerFile = caseFile(workDir, caseNumber, ".ans");
                    if (!Files.isRegularFile(answerFile)) {
                        answerFile = caseFile(workDir, caseNumber, ".out");
                    }
                    if (Files.isRegularFile(answerFile)) {
                        addFileToZip(zipStream, answerFile, caseNumber + ".out");
                    }
                }
            }
        };
    }

    public String archiveFilename(String judgeId) {
        String safeJudgeId = judgeId == null ? "unknown" : judgeId.replaceAll("[^A-Za-z0-9._-]", "_");
        return "testcases-" + safeJudgeId + ".zip";
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
        if (!Files.isDirectory(workDir)) {
            throw new IOException("Judge task files not found");
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
        return file;
    }

    private String readRequiredUtf8(Path file, long maxBytes, String label) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException(label + " file not found");
        }
        if (Files.size(file) > maxBytes) {
            throw new IOException(label + " file exceeds configured byte limit");
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private long detailLimit(JudgeTask task) {
        if (task.getPolicy() == null || task.getPolicy().maxOutputBytesPerCase() <= 0) {
            return DEFAULT_DETAIL_LIMIT_BYTES;
        }
        return task.getPolicy().maxOutputBytesPerCase();
    }

    private List<Path> listInputFiles(JudgeTask task, Path workDir) throws IOException {
        try (Stream<Path> stream = Files.list(workDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> INPUT_FILE_NAME.matcher(path.getFileName().toString()).matches())
                    .filter(path -> {
                        int caseNumber = parseCaseNumber(path);
                        return caseNumber >= 1 && caseNumber <= task.getRequestedCases();
                    })
                    .sorted(Comparator.comparingInt(this::parseCaseNumber))
                    .toList();
        }
    }

    private void addFileToZip(ZipOutputStream zipStream, Path file, String entryName) throws IOException {
        if (!SAFE_ENTRY_NAME.matcher(entryName).matches()) {
            throw new IOException("Unsafe zip entry name");
        }
        zipStream.putNextEntry(new ZipEntry(entryName));
        try (InputStream inputStream = Files.newInputStream(file)) {
            inputStream.transferTo(zipStream);
        }
        zipStream.closeEntry();
    }

    private int parseCaseNumber(Path file) {
        String name = file.getFileName().toString();
        int dotIndex = name.indexOf('.');
        String numberPart = dotIndex >= 0 ? name.substring(0, dotIndex) : name;
        try {
            return Integer.parseInt(numberPart);
        } catch (NumberFormatException ex) {
            return Integer.MAX_VALUE;
        }
    }
}
