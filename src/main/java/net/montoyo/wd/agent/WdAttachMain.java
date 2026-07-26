package net.montoyo.wd.agent;

import com.sun.tools.attach.VirtualMachine;

/**
 * Tiny helper program run in a SEPARATE short-lived JVM: attaches to the
 * server JVM (a process cannot attach to itself without
 * -Djdk.attach.allowAttachSelf) and loads {@link GlfwStubAgent} into it.
 *
 * Usage: java -cp <agent.jar> net.montoyo.wd.agent.WdAttachMain <pid> <agent.jar>
 */
public final class WdAttachMain {
    private WdAttachMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: WdAttachMain <pid> <agentJar>");
            System.exit(2);
        }

        VirtualMachine vm = VirtualMachine.attach(args[0]);
        try {
            // Second argument = agent options: the jar to add to the bootstrap path
            vm.loadAgent(args[1], args[1]);
        } finally {
            vm.detach();
        }
    }
}
