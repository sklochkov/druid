public class CgroupCpuTest {
    public static void main(String[] args) {
        System.out.println("=== Java Cgroup CPU Detection Test ===\n");
        
        // What Runtime.availableProcessors() returns (used by our code)
        int availProcs = Runtime.getRuntime().availableProcessors();
        System.out.println("Runtime.availableProcessors(): " + availProcs);
        
        // What Netty would create by default (before our cap)
        int nettyWorkers = availProcs * 2;
        System.out.println("Netty workers (cores × 2):     " + nettyWorkers);
        
        // What our code creates (with cap)
        int cappedWorkers = Math.min(nettyWorkers, 8);
        System.out.println("With MAX_WORKER_THREADS=8:     " + cappedWorkers);
        
        System.out.println("\n=== Memory Impact ===");
        System.out.println("Uncapped: " + nettyWorkers + " threads × ~1MB = ~" + nettyWorkers + "MB (thread stacks alone)");
        System.out.println("Capped:   " + cappedWorkers + " threads × ~1MB = ~" + cappedWorkers + "MB (thread stacks alone)");
        
        System.out.println("\n=== Cgroup Detection ===");
        
        // Try to read cgroup CPU quota (cgroup v2)
        try {
            String cpuMax = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("/sys/fs/cgroup/cpu.max")));
            String[] parts = cpuMax.trim().split("\\s+");
            if (parts.length >= 2 && !parts[0].equals("max")) {
                long quota = Long.parseLong(parts[0]);
                long period = Long.parseLong(parts[1]);
                int effectiveCpus = (int)(quota / period);
                System.out.println("Cgroup v2 detected:");
                System.out.println("  CPU Quota:  " + quota + "us");
                System.out.println("  CPU Period: " + period + "us");
                System.out.println("  Effective CPUs: " + effectiveCpus);
                System.out.println("\nDISCREPANCY: JVM reports " + availProcs + " but cgroup limit is " + effectiveCpus);
            }
        } catch (Exception e) {
            System.out.println("Cgroup v2 not available: " + e.getMessage());
        }
        
        // Try cgroup v1
        try {
            String quota = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("/sys/fs/cgroup/cpu/cpu.cfs_quota_us"))).trim();
            String period = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("/sys/fs/cgroup/cpu/cpu.cfs_period_us"))).trim();
            
            if (!quota.equals("-1")) {
                long quotaVal = Long.parseLong(quota);
                long periodVal = Long.parseLong(period);
                int effectiveCpus = (int)(quotaVal / periodVal);
                System.out.println("Cgroup v1 detected:");
                System.out.println("  CPU Quota:  " + quotaVal + "us");
                System.out.println("  CPU Period: " + periodVal + "us");
                System.out.println("  Effective CPUs: " + effectiveCpus);
                System.out.println("\nDISCREPANCY: JVM reports " + availProcs + " but cgroup limit is " + effectiveCpus);
            }
        } catch (Exception e) {
            System.out.println("Cgroup v1 not available: " + e.getMessage());
        }
        
        System.out.println("\n=== Conclusion ===");
        if (availProcs > 10) {
            System.out.println("⚠️  JVM is NOT respecting cgroup limits!");
            System.out.println("   This will cause excessive thread creation in Netty");
            System.out.println("   MAX_WORKER_THREADS cap is CRITICAL");
        } else {
            System.out.println("✓ JVM is respecting cgroup limits");
            System.out.println("  MAX_WORKER_THREADS cap is just a safety measure");
        }
    }
}

