package com.paicli.tui;

import com.paicli.agent.Agent;
import com.paicli.config.PaiCliConfig;
import com.paicli.hitl.HitlHandler;
import com.paicli.llm.LlmClient;
import com.paicli.tui.history.ConversationSnapshot;
import com.paicli.tui.pane.CenterPane;
import com.paicli.tui.pane.StatusPane;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TuiSessionControllerTest {

    @Test
    void clearIsRejectedWhileTaskIsRunning() throws Exception {
        Agent agent = mock(Agent.class);
        CenterPane centerPane = mock(CenterPane.class);
        ConversationSnapshot history = mock(ConversationSnapshot.class);
        TuiSessionController controller = new TuiSessionController(
                mock(PaiCliConfig.class),
                mock(LlmClient.class),
                agent,
                mock(HitlHandler.class),
                centerPane,
                mock(StatusPane.class),
                () -> { },
                () -> { },
                Runnable::run,
                history);
        try {
            Field currentTask = TuiSessionController.class.getDeclaredField("currentTask");
            currentTask.setAccessible(true);
            currentTask.set(controller, new CompletableFuture<>());

            controller.submit("/clear");

            verify(agent, never()).clearHistory();
            verify(centerPane).appendSystemMessage(contains("任务仍在运行"));
        } finally {
            controller.close();
        }
    }

    @Test
    void clearRemainsRejectedAfterFutureCancellationUntilRunActuallyExits() throws Exception {
        Agent agent = mock(Agent.class);
        CenterPane centerPane = mock(CenterPane.class);
        ConversationSnapshot history = mock(ConversationSnapshot.class);
        TuiSessionController controller = new TuiSessionController(
                mock(PaiCliConfig.class), mock(LlmClient.class), agent, mock(HitlHandler.class),
                centerPane, mock(StatusPane.class), () -> { }, () -> { }, Runnable::run, history);
        try {
            Field activeRun = TuiSessionController.class.getDeclaredField("activeRun");
            activeRun.setAccessible(true);
            ((AtomicBoolean) activeRun.get(controller)).set(true);
            Field currentTask = TuiSessionController.class.getDeclaredField("currentTask");
            currentTask.setAccessible(true);
            currentTask.set(controller, CompletableFuture.completedFuture(null));

            controller.submit("/clear");

            verify(agent, never()).clearHistory();
            verify(centerPane).appendSystemMessage(contains("任务仍在运行"));
        } finally {
            controller.close();
        }
    }
}
