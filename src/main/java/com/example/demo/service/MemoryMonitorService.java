package com.example.demo.service;

import com.example.demo.config.MemoryConfiguration;
import com.example.demo.exception.MemoryLimitExceededException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存监控服务
 * 用于监控进程的内存使用情况，实现内存限制功能
 */
@Service
@RequiredArgsConstructor
public class MemoryMonitorService {
    
    private final MemoryConfiguration memoryConfig;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);
    
    /**
     * 内存监控结果
     */
    public record MemoryUsage(long virtualMemory, long physicalMemory, long peakMemory) {}
    
    /**
     * 监控进程内存使用情况
     * 
     * @param process 要监控的进程
     * @param memoryLimit 内存限制（字节）
     * @return CompletableFuture包装的内存使用结果
     */
    public CompletableFuture<MemoryUsage> monitorProcess(Process process, long memoryLimit) {
        AtomicLong peakMemory = new AtomicLong(0);
        AtomicLong currentVirtual = new AtomicLong(0);
        AtomicLong currentPhysical = new AtomicLong(0);
        AtomicBoolean memoryExceeded = new AtomicBoolean(false);
        
        CompletableFuture<MemoryUsage> future = new CompletableFuture<>();
        
        // 定期检查内存使用情况
        var monitorTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (!process.isAlive()) {
                    // 进程已结束，返回结果
                    future.complete(new MemoryUsage(
                        currentVirtual.get(), 
                        currentPhysical.get(), 
                        peakMemory.get()
                    ));
                    return;
                }
                
                MemoryUsage usage = getProcessMemoryUsage(process.pid());
                if (usage != null) {
                    currentVirtual.set(usage.virtualMemory());
                    currentPhysical.set(usage.physicalMemory());
                    
                    // 更新峰值内存
                    long currentPeak = Math.max(usage.virtualMemory(), usage.physicalMemory());
                    peakMemory.updateAndGet(existing -> Math.max(existing, currentPeak));
                    
                    // 检查是否超出内存限制
                    if (usage.physicalMemory() > memoryLimit && memoryExceeded.compareAndSet(false, true)) {
                        process.destroyForcibly();
                        future.completeExceptionally(new MemoryLimitExceededException(
                            usage.physicalMemory(), memoryLimit
                        ));
                        return;
                    }
                }
            } catch (Exception e) {
                if (!future.isDone()) {
                    future.completeExceptionally(e);
                }
            }
        }, 0, memoryConfig.getCheckInterval(), TimeUnit.MILLISECONDS);
        
        // 当future完成时取消监控任务
        future.whenComplete((result, throwable) -> monitorTask.cancel(true));
        
        return future;
    }
    
    /**
     * 获取进程的内存使用情况
     * 
     * @param pid 进程ID
     * @return 内存使用情况，如果获取失败返回null
     */
    private MemoryUsage getProcessMemoryUsage(long pid) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("windows");
        
        if (isWindows) {
            // Windows系统使用wmic命令
            return getMemoryUsageWindows(pid);
        } else {
            // Linux系统使用/proc文件系统
            return getMemoryUsageLinux(pid);
        }
    }
    
    /**
     * Linux系统获取内存使用情况
     */
    private MemoryUsage getMemoryUsageLinux(long pid) {
        try {
            // 在Linux系统上读取/proc/[pid]/status文件获取内存信息
            ProcessBuilder pb = new ProcessBuilder("cat", "/proc/" + pid + "/status");
            Process proc = pb.start();
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                long vmRSS = 0; // 物理内存 (KB)
                long vmSize = 0; // 虚拟内存 (KB)
                
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("VmRSS:")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 2) {
                            vmRSS = Long.parseLong(parts[1]) * 1024; // 转换为字节
                        }
                    } else if (line.startsWith("VmSize:")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 2) {
                            vmSize = Long.parseLong(parts[1]) * 1024; // 转换为字节
                        }
                    }
                }
                
                return new MemoryUsage(vmSize, vmRSS, 0);
            }
        } catch (IOException | NumberFormatException e) {
            // 如果无法获取内存信息，尝试使用ps命令作为备选方案
            return getMemoryUsageWithPs(pid);
        }
    }
    
    /**
     * Windows系统获取内存使用情况
     */
    private MemoryUsage getMemoryUsageWindows(long pid) {
        try {
            // 使用wmic命令获取内存信息
            ProcessBuilder pb = new ProcessBuilder("wmic", "process", "where", "ProcessId=" + pid, 
                                                 "get", "WorkingSetSize,VirtualSize", "/format:csv");
            Process proc = pb.start();
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                // 跳过标题行
                reader.readLine();
                line = reader.readLine();
                
                if (line != null && !line.trim().isEmpty()) {
                    String[] parts = line.split(",");
                    if (parts.length >= 3) {
                        try {
                            // WorkingSetSize是物理内存，VirtualSize是虚拟内存
                            long workingSetSize = Long.parseLong(parts[2].trim()); // 物理内存 (bytes)
                            long virtualSize = Long.parseLong(parts[1].trim());    // 虚拟内存 (bytes)
                            return new MemoryUsage(virtualSize, workingSetSize, 0);
                        } catch (NumberFormatException e) {
                            // 解析失败，返回null
                        }
                    }
                }
            }
        } catch (IOException e) {
            // wmic命令失败，使用tasklist作为备选
            return getMemoryUsageWithTasklist(pid);
        }
        return null;
    }
    
    /**
     * Windows系统使用tasklist命令获取内存使用情况（备选方案）
     */
    private MemoryUsage getMemoryUsageWithTasklist(long pid) {
        try {
            ProcessBuilder pb = new ProcessBuilder("tasklist", "/fi", "PID eq " + pid, "/fo", "csv");
            Process proc = pb.start();
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                // 跳过标题行
                reader.readLine();
                line = reader.readLine();
                
                if (line != null && !line.trim().isEmpty()) {
                    String[] parts = line.split(",");
                    if (parts.length >= 5) {
                        try {
                            // 第5列是内存使用（格式如 "12,345 K"）
                            String memStr = parts[4].trim().replace("\"", "").replace(",", "").replace(" K", "");
                            long memoryKB = Long.parseLong(memStr);
                            long memoryBytes = memoryKB * 1024;
                            return new MemoryUsage(memoryBytes, memoryBytes, 0);
                        } catch (NumberFormatException e) {
                            // 解析失败
                        }
                    }
                }
            }
        } catch (IOException e) {
            // tasklist命令也失败了
        }
        return null;
    }
    
    /**
     * 使用ps命令获取内存使用情况（备选方案）
     */
    private MemoryUsage getMemoryUsageWithPs(long pid) {
        try {
            ProcessBuilder pb = new ProcessBuilder("ps", "-o", "vsz,rss", "-p", String.valueOf(pid));
            Process proc = pb.start();
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                reader.readLine(); // 跳过标题行
                String line = reader.readLine();
                if (line != null) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) {
                        long vsz = Long.parseLong(parts[0]) * 1024; // VSZ in KB, convert to bytes
                        long rss = Long.parseLong(parts[1]) * 1024; // RSS in KB, convert to bytes
                        return new MemoryUsage(vsz, rss, 0);
                    }
                }
            }
        } catch (IOException | NumberFormatException e) {
            // 如果都失败了，返回null
        }
        return null;
    }
    
    /**
     * 格式化内存大小为人类可读的格式
     */
    public String formatMemorySize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }
    
    /**
     * 关闭内存监控服务
     */
    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}