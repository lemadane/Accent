package accent.compiler;

import java.util.concurrent.Callable;

/** Java interop operation invoked by compiled Accent Async.start calls. */
public final class AsyncConcurrencyProbe {
    private final Callable<String> operation;

    public AsyncConcurrencyProbe(Callable<String> operation) {
        this.operation = operation;
    }

    public void runVoid() throws Exception {
        operation.call();
    }

    public String run() throws Exception {
        return operation.call();
    }
}
