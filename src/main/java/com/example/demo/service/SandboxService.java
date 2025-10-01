package com.example.demo.service;

import com.example.demo.config.SandboxConfiguration;
import com.example.demo.exception.SecurityViolationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 安全沙箱服务
 * 提供代码执行的安全隔离环境，限制文件系统访问、网络访问和系统调用
 */
@Service
@RequiredArgsConstructor
public class SandboxService {
    
    private final SandboxConfiguration sandboxConfig;
    
    /**
     * 沙箱执行结果
     */
    public record SandboxResult(
        int exitCode,
        String output,
        String error,
        long executionTime,
        boolean securityViolation,
        String violationReason
    ) {}
    
    /**
     * 在沙箱环境中执行命令
     * 
     * @param command 要执行的命令
     * @param workingDir 工作目录
     * @param inputFile 输入文件
     * @param outputFile 输出文件
     * @param timeLimit 时间限制（毫秒）
     * @param memoryLimit 内存限制（字节）
     * @return 沙箱执行结果
     */
    public SandboxResult executeInSandbox(
            String[] command, 
            Path workingDir, 
            Path inputFile, 
            Path outputFile, 
            long timeLimit, 
            long memoryLimit) throws IOException, InterruptedException {
        
        System.out.println("开始沙箱执行: " + String.join(" ", command));
        System.out.println("工作目录: " + workingDir);
        System.out.println("沙箱启用状态: " + sandboxConfig.isEnabled());
        System.out.println("操作系统: " + System.getProperty("os.name"));
        
        // 检查操作系统
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("windows");
        
        if (!sandboxConfig.isEnabled() || isWindows) {
            // 如果沙箱未启用或在Windows系统下，直接执行命令
            System.out.println(isWindows ? "Windows系统，直接执行命令" : "沙箱未启用，直接执行命令");
            return executeDirectly(command, workingDir, inputFile, outputFile, timeLimit);
        }
        
        // Linux系统下的沙箱执行
        return executeLinuxSandbox(command, workingDir, inputFile, outputFile, timeLimit, memoryLimit);
    }
    
    /**
     * Linux系统下的沙箱执行
     */
    private SandboxResult executeLinuxSandbox(
            String[] command, 
            Path workingDir, 
            Path inputFile, 
            Path outputFile, 
            long timeLimit, 
            long memoryLimit) throws IOException, InterruptedException {
        
        // 检查firejail是否可用
        boolean firejailAvailable = isFirejailAvailable();
        System.out.println("Firejail可用性: " + firejailAvailable);
        
        if (!firejailAvailable) {
            // 如果firejail不可用，回退到直接执行但添加基本限制
            System.out.println("Firejail不可用，回退到直接执行");
            return executeWithBasicLimits(command, workingDir, inputFile, outputFile, timeLimit, memoryLimit);
        }
        
        // 使用firejail沙箱执行
        return executeWithFirejail(command, workingDir, inputFile, outputFile, timeLimit, memoryLimit);
    }
    
    /**
     * 使用基本限制执行（无firejail时的备选方案）
     */
    private SandboxResult executeWithBasicLimits(
            String[] command, 
            Path workingDir, 
            Path inputFile, 
            Path outputFile, 
            long timeLimit, 
            long memoryLimit) throws IOException, InterruptedException {
        
        // 检查timeout命令是否可用
        boolean timeoutAvailable = isTimeoutAvailable();
        System.out.println("Timeout命令可用性: " + timeoutAvailable);
        
        if (timeoutAvailable) {
            // 使用timeout命令添加基本的时间限制
            List<String> limitedCommand = new ArrayList<>();
            limitedCommand.add("timeout");
            limitedCommand.add(String.valueOf(timeLimit / 1000 + 5)); // 转换为秒，加5秒缓冲
            limitedCommand.addAll(Arrays.asList(command));
            
            return executeDirectly(limitedCommand.toArray(new String[0]), workingDir, inputFile, outputFile, timeLimit);
        } else {
            // 如果timeout也不可用，只能直接执行
            System.out.println("Timeout命令不可用，直接执行");
            return executeDirectly(command, workingDir, inputFile, outputFile, timeLimit);
        }
    }
    
    /**
     * 检查timeout命令是否可用
     */
    private boolean isTimeoutAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("which", "timeout");
            Process process = pb.start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 使用firejail执行沙箱
     */
    private SandboxResult executeWithFirejail(
            String[] command, 
            Path workingDir, 
            Path inputFile, 
            Path outputFile, 
            long timeLimit, 
            long memoryLimit) throws IOException, InterruptedException {
        
        // 创建受限的沙箱环境
        Path sandboxDir = createSandboxEnvironment(workingDir);
        System.out.println("创建沙箱环境: " + sandboxDir);
        
        try {
            // 构建安全的执行命令
            // 注意：在沙箱中，可执行文件的路径需要相对于沙箱目录
            String executableName = Paths.get(command[0]).getFileName().toString();
            String[] sandboxCommand = { "./" + executableName };
            List<String> secureCommand = buildSecureCommand(sandboxCommand, sandboxDir, inputFile, outputFile, memoryLimit);
            System.out.println("安全命令: " + String.join(" ", secureCommand));
            
            ProcessBuilder processBuilder = new ProcessBuilder(secureCommand);
            processBuilder.directory(sandboxDir.toFile());
            
            // 处理输入输出重定向 - 在沙箱中需要映射到沙箱内的文件
            if (inputFile != null) {
                Path sandboxInputFile = sandboxDir.resolve(inputFile.getFileName());
                if (Files.exists(sandboxInputFile)) {
                    processBuilder.redirectInput(sandboxInputFile.toFile());
                    System.out.println("沙箱输入重定向: " + sandboxInputFile);
                } else {
                    System.err.println("沙箱输入文件不存在: " + sandboxInputFile);
                }
            }
            
            if (outputFile != null) {
                Path sandboxOutputFile = sandboxDir.resolve(outputFile.getFileName());
                processBuilder.redirectOutput(sandboxOutputFile.toFile());
                System.out.println("沙箱输出重定向: " + sandboxOutputFile);
            }
            
            // 设置环境变量限制
            Map<String, String> env = processBuilder.environment();
            env.clear(); // 清除所有环境变量
            env.put("PATH", "/usr/bin:/bin"); // 只保留基本的PATH
            env.put("HOME", sandboxDir.toString());
            env.put("TMP", sandboxDir.toString());
            env.put("TMPDIR", sandboxDir.toString());
            
            long startTime = System.currentTimeMillis();
            Process process = processBuilder.start();
            
            boolean finished = process.waitFor(timeLimit, TimeUnit.MILLISECONDS);
            long executionTime = System.currentTimeMillis() - startTime;
            
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            
            int exitCode = process.exitValue();
            System.out.println("进程执行完成: exitCode=" + exitCode + ", executionTime=" + executionTime);
            
            // 检查是否有安全违规
            boolean securityViolation = false;
            String violationReason = null;
            
            if (exitCode == 137) { // SIGKILL - 可能是因为违反沙箱规则
                securityViolation = true;
                violationReason = "Process killed by sandbox security policy";
            }
            
            String output = "";
            String error = "";
            
            // 从沙箱内的输出文件读取结果
            if (outputFile != null) {
                Path sandboxOutputFile = sandboxDir.resolve(outputFile.getFileName());
                if (Files.exists(sandboxOutputFile)) {
                    output = Files.readString(sandboxOutputFile);
                    // 将结果复制回原始输出文件位置
                    Files.copy(sandboxOutputFile, outputFile, StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("复制输出文件: " + sandboxOutputFile + " -> " + outputFile);
                }
            }
            
            return new SandboxResult(exitCode, output, error, executionTime, securityViolation, violationReason);
            
        } catch (Exception e) {
            System.err.println("沙箱执行异常: " + e.getMessage());
            e.printStackTrace();
            throw e;
        } finally {
            // 清理沙箱环境
            cleanupSandboxEnvironment(sandboxDir);
        }
    }
    
    /**
     * 创建沙箱环境
     */
    private Path createSandboxEnvironment(Path workingDir) throws IOException {
        Path sandboxDir = Files.createTempDirectory(Paths.get(sandboxConfig.getBaseDirectory()), "sandbox-");
        
        // 设置严格的目录权限
        Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rwx------");
        Files.setPosixFilePermissions(sandboxDir, permissions);
        
        // 复制必要的文件到沙箱
        copyNecessaryFiles(workingDir, sandboxDir);
        
        return sandboxDir;
    }
    
    /**
     * 构建安全的执行命令
     */
    private List<String> buildSecureCommand(String[] originalCommand, Path sandboxDir, Path inputFile, Path outputFile, long memoryLimit) {
        List<String> secureCommand = new ArrayList<>();
        
        // 检查firejail是否可用
        boolean firejailAvailable = isFirejailAvailable();
        System.out.println("Firejail可用性: " + firejailAvailable);
        
        if (firejailAvailable) {
            secureCommand.add("firejail");
            secureCommand.add("--quiet");
            secureCommand.add("--private=" + sandboxDir.toString());
            
            if (sandboxConfig.isNetworkDisabled()) {
                secureCommand.add("--net=none"); // 禁用网络
            }
            
            secureCommand.add("--noroot"); // 禁用root权限
            secureCommand.add("--nosound"); // 禁用声音
            secureCommand.add("--novideo"); // 禁用视频
            secureCommand.add("--no3d"); // 禁用3D加速
            secureCommand.add("--nodvd"); // 禁用DVD
            secureCommand.add("--notv"); // 禁用TV
            secureCommand.add("--nou2f"); // 禁用U2F
            secureCommand.add("--seccomp"); // 启用seccomp过滤
            secureCommand.add("--caps.drop=all"); // 删除所有capabilities
            
            // 内存限制
            if (memoryLimit > 0) {
                long memoryLimitMB = memoryLimit / (1024 * 1024);
                secureCommand.add("--rlimit-as=" + memoryLimitMB * 1024 * 1024); // 虚拟内存限制
            }
            
            // 进程数量限制
            secureCommand.add("--rlimit-nproc=" + sandboxConfig.getMaxProcesses());
            
            // 文件大小限制
            secureCommand.add("--rlimit-fsize=104857600"); // 100MB文件大小限制
            
        } else {
            // 如果firejail不可用，使用timeout和ulimit作为基本限制
            System.out.println("Firejail不可用，使用基本限制");
            secureCommand.add("timeout");
            secureCommand.add("30s"); // 30秒超时
        }
        
        // 添加原始命令
        secureCommand.addAll(Arrays.asList(originalCommand));
        
        return secureCommand;
    }
    
    /**
     * 检查firejail是否可用
     */
    private boolean isFirejailAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("which", "firejail");
            Process process = pb.start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 复制必要的文件到沙箱
     */
    private void copyNecessaryFiles(Path source, Path target) throws IOException {
        System.out.println("复制文件从 " + source + " 到 " + target);
        
        // 只复制可执行文件和输入文件
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(source)) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    String fileName = entry.getFileName().toString();
                    System.out.println("检查文件: " + fileName);
                    
                    // 只复制可执行文件、输入文件和必要的库文件
                    if (fileName.endsWith(".in") || 
                        fileName.endsWith(".out") || 
                        fileName.endsWith(".ans") ||
                        Files.isExecutable(entry)) {
                        
                        Path targetPath = target.resolve(fileName);
                        Files.copy(entry, targetPath, StandardCopyOption.REPLACE_EXISTING);
                        
                        // 如果是可执行文件，确保它有执行权限
                        if (Files.isExecutable(entry)) {
                            try {
                                Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rwx------");
                                Files.setPosixFilePermissions(targetPath, permissions);
                                System.out.println("设置可执行权限: " + fileName);
                            } catch (Exception e) {
                                System.err.println("设置权限失败: " + fileName + " - " + e.getMessage());
                            }
                        }
                        
                        System.out.println("复制文件: " + fileName);
                    }
                }
            }
        }
    }
    
    /**
     * 清理沙箱环境
     */
    private void cleanupSandboxEnvironment(Path sandboxDir) {
        try {
            if (Files.exists(sandboxDir)) {
                Files.walk(sandboxDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            // 忽略删除错误
                        }
                    });
            }
        } catch (IOException e) {
            // 忽略清理错误
        }
    }
    
    /**
     * 直接执行命令（沙箱未启用时的备选方案）
     */
    private SandboxResult executeDirectly(String[] command, Path workingDir, Path inputFile, Path outputFile, long timeLimit) 
            throws IOException, InterruptedException {
        
        System.out.println("直接执行命令: " + String.join(" ", command));
        System.out.println("工作目录: " + workingDir);
        
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workingDir.toFile());
        
        if (inputFile != null) {
            processBuilder.redirectInput(inputFile.toFile());
            System.out.println("输入重定向: " + inputFile);
        }
        if (outputFile != null) {
            processBuilder.redirectOutput(outputFile.toFile());
            System.out.println("输出重定向: " + outputFile);
        }
        
        // 创建错误输出文件
        File errorFile = Files.createTempFile("sandbox_error_", ".log").toFile();
        processBuilder.redirectError(errorFile);
        
        long startTime = System.currentTimeMillis();
        Process process = processBuilder.start();
        
        boolean finished = process.waitFor(timeLimit, TimeUnit.MILLISECONDS);
        long executionTime = System.currentTimeMillis() - startTime;
        
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        
        int exitCode = process.exitValue();
        String output = outputFile != null && Files.exists(outputFile) ? Files.readString(outputFile) : "";
        String error = Files.exists(errorFile.toPath()) ? Files.readString(errorFile.toPath()) : "";
        
        // 清理错误文件
        try {
            Files.deleteIfExists(errorFile.toPath());
        } catch (Exception e) {
            // 忽略删除错误
        }
        
        System.out.println("直接执行完成: exitCode=" + exitCode + ", executionTime=" + executionTime + "ms");
        if (!error.trim().isEmpty()) {
            System.err.println("错误输出: " + error);
        }
        
        return new SandboxResult(exitCode, output, error, executionTime, false, null);
    }
    
    /**
     * 验证路径是否在允许的范围内
     */
    public boolean isPathAllowed(Path path) {
        if (!sandboxConfig.isEnabled()) {
            return true;
        }
        
        Path normalizedPath = path.normalize().toAbsolutePath();
        Path allowedBase = Paths.get(sandboxConfig.getBaseDirectory()).normalize().toAbsolutePath();
        
        return normalizedPath.startsWith(allowedBase);
    }
    
    /**
     * 检查文件系统访问权限
     */
    public void checkFileSystemAccess(Path path) throws SecurityViolationException {
        if (!isPathAllowed(path)) {
            throw new SecurityViolationException("FILE_ACCESS_DENIED", "Access to path outside sandbox: " + path.toString());
        }
    }
}