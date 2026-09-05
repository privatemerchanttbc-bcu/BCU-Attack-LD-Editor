package manualcontrol.attackform.editor;

import common.pack.PackData;
import common.pack.Source;
import manualcontrol.attackform.AttackFormLog;

import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class AttackFormSaveCoordinator {

    private static volatile Method workspaceSave;

    private AttackFormSaveCoordinator() {}

    public static boolean rerouteIfNeeded(final Object source, final Object pack,
                                          final boolean auto) {
        if (SwingUtilities.isEventDispatchThread()
                || !AttackFormEditorHooks.requiresEdtSave(pack)) return false;

        final Throwable[] failure = new Throwable[1];
        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    try {
                        saveMethod().invoke(source, pack, Boolean.valueOf(auto));
                    } catch (Throwable error) {
                        failure[0] = unwrap(error);
                    }
                }
            });
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failure[0] = interrupted;
        } catch (InvocationTargetException error) {
            failure[0] = unwrap(error);
        }

        if (failure[0] != null) sneakyThrow(failure[0]);
        AttackFormLog.debug(auto ? "autosave-edt" : "save-edt",
                "Workspace.save completed without exposing a secondary attack overlay");
        return true;
    }

    private static Method saveMethod() throws NoSuchMethodException {
        Method method = workspaceSave;
        if (method != null) return method;
        synchronized (AttackFormSaveCoordinator.class) {
            method = workspaceSave;
            if (method == null) {
                method = Source.Workspace.class.getDeclaredMethod("save",
                        PackData.UserPack.class, Boolean.TYPE);
                method.setAccessible(true);
                workspaceSave = method;
            }
        }
        return method;
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof InvocationTargetException
                && ((InvocationTargetException) error).getCause() != null)
            return ((InvocationTargetException) error).getCause();
        return error;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable error) throws T {
        throw (T) error;
    }
}
