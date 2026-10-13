package com.paicli.eval;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 并行执行时分配互不冲突的端口。
 *
 * <p>直接用「开一个 ServerSocket(0) 拿到端口再关掉」有 TOCTOU 窗口：
 * 关闭到子进程真正 bind 之间，另一个线程可能拿到同一个端口。这里额外维护一份
 * 本进程已发出的端口集合，把同 JVM 内的冲突挡掉；跨进程的残余窗口靠临时端口
 * 范围足够大来兜底，真撞上时子进程启动失败会被记成 case error 而不是静默串号。</p>
 */
public final class PortAllocator {

    private final Set<Integer> handedOut = ConcurrentHashMap.newKeySet();

    public int allocate() throws IOException {
        for (int attempt = 0; attempt < 50; attempt++) {
            int port;
            try (ServerSocket socket = new ServerSocket(0)) {
                port = socket.getLocalPort();
            }
            if (handedOut.add(port)) {
                return port;
            }
        }
        throw new IOException("50 次尝试内没拿到未占用端口");
    }

    /** case 结束后归还，长时间运行不至于把集合撑大。 */
    public void release(int port) {
        handedOut.remove(port);
    }
}
