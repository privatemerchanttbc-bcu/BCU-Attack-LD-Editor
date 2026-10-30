package bcu.attackld.install;

import javax.swing.JOptionPane;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;

public final class AttackLdInstaller {

    private static final String MODULE_DIR = "bcu-attack-ld-editor";
    private static final String AGENT = "attack-ld-editor-agent.jar";
    private static final String PAYLOAD = "/payload/" + AGENT;
    private static final String FEATURE_DIR = "bcu-features";
    private static final String REGISTRATION = "100-attack-ld-editor.jvmargs";
    private static final String LAUNCHER = "BCU New Features.bat";
    private static final String HIDDEN = "BCU New Features (No Console).vbs";
    private static final String LEGACY_LAUNCHER = "BCU Attack LD Editor.bat";
    private static final String LEGACY_HIDDEN = "BCU Attack LD Editor (No Console).vbs";
    private static final String LEGACY_UNINSTALL = "Uninstall BCU Attack LD Editor.bat";

    public static void main(String[] args) {
        try {
            boolean uninstall = false;
            String path = null;
            for (String arg : args) {
                if ("--uninstall".equals(arg) || "-u".equals(arg)) uninstall = true;
                else if (!arg.startsWith("-")) path = arg;
            }
            File root = path == null ? installerDir() : new File(path);
            if (root.isFile()) root = root.getParentFile();
            if (path == null && findBcu(root) == null && root != null
                    && findBcu(root.getParentFile()) != null) root = root.getParentFile();
            if (root == null || !root.isDirectory())
                throw new InstallError("Could not locate the BCU folder.");

            if (uninstall) {
                unregister(root);
                ok("Attack LD Editor was removed from the shared launcher. "
                        + "The module folder and user data were retained.\n\n"
                        + root.getAbsolutePath());
                return;
            }
            if (findBcu(root) == null)
                throw new InstallError("No BCU*.jar was found in:\n" + root.getAbsolutePath()
                        + "\n\nPut this installer next to the original BCU jar and run it again.");
            install(root);
            ok("BCU Attack LD Editor registered in the shared feature launcher.\n\nLaunch with:\n"
                    + new File(root, HIDDEN).getAbsolutePath()
                    + "\n\nUse the .bat launcher when diagnostic output is needed.");
        } catch (InstallError error) {
            fail(error.getMessage());
        } catch (Throwable error) {
            fail("Unexpected installation error: " + error);
        }
    }

    private static void install(File root) throws Exception {
        File module = new File(root, MODULE_DIR);
        if (!module.exists() && !module.mkdirs())
            throw new InstallError("Could not create:\n" + module.getAbsolutePath());
        extractAgent(new File(module, AGENT));
        File features = new File(root, FEATURE_DIR);
        if (!features.exists() && !features.mkdirs())
            throw new InstallError("Could not create:\n" + features.getAbsolutePath());
        write(new File(features, REGISTRATION), featureRegistration());
        write(new File(root, LAUNCHER), sharedLauncher());
        write(new File(root, HIDDEN), hiddenLauncher());

        delete(new File(root, LEGACY_LAUNCHER));
        delete(new File(root, LEGACY_HIDDEN));
        delete(new File(root, LEGACY_UNINSTALL));
        delete(new File(module, LEGACY_LAUNCHER));
        write(new File(module, "INSTALL_VERSION.txt"),
                "BCU Attack LD Editor 1.0.2\nInstalled: "
                        + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "\n");
        write(new File(module, "README.txt"), installedReadme());
    }

    private static void extractAgent(File target) throws Exception {
        InputStream input = AttackLdInstaller.class.getResourceAsStream(PAYLOAD);
        if (input == null) throw new InstallError("Embedded agent payload is missing.");
        File temp = File.createTempFile("attack-ld-editor-", ".jar", target.getParentFile());
        try {
            OutputStream output = new FileOutputStream(temp);
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            } finally {
                output.close();
                input.close();
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (temp.exists()) temp.delete();
        }
    }

    private static File findBcu(File root) {
        if (root == null) return null;
        File[] files = root.listFiles();
        if (files == null) return null;
        File newest = null;
        for (File file : files) {
            String name = file.getName().toLowerCase();
            if (!file.isFile() || !name.startsWith("bcu") || !name.endsWith(".jar")) continue;
            if (name.contains("installer") || name.contains("attack-ld")
                    || name.contains("manual-control") || name.contains("backup")
                    || name.contains("patch")) continue;
            if (newest == null || file.lastModified() > newest.lastModified()) newest = file;
        }
        return newest;
    }

    private static void unregister(File root) throws Exception {
        File features = new File(root, FEATURE_DIR);
        delete(new File(features, REGISTRATION));
        delete(new File(root, LEGACY_LAUNCHER));
        delete(new File(root, LEGACY_HIDDEN));
        delete(new File(root, LEGACY_UNINSTALL));
        File[] registrations = features.listFiles((dir, name) -> name.endsWith(".jvmargs"));
        if (registrations == null || registrations.length == 0) {
            delete(new File(root, LAUNCHER));
            delete(new File(root, HIDDEN));
            if (features.isDirectory()) features.delete();
        }
    }

    private static void delete(File file) throws Exception {
        if (file.isFile() && !file.delete())
            throw new InstallError("Could not delete:\n" + file.getAbsolutePath());
    }

    private static void write(File file, String text) throws Exception {
        Files.write(file.toPath(), text.replace("\n", "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String featureRegistration() {
        return "# One JVM argument per line. Files are loaded alphabetically.\n"
                + "\"-Dmanualcontrol.home=!BCU_ROOT!\\" + MODULE_DIR + "\"\n"
                + "\"-Dmanualcontrol.log=!BCU_ROOT!\\" + MODULE_DIR
                + "\\attack-ld-editor.log\"\n"
                + "\"-Dmanualcontrol.features=switch_attack_form\"\n"
                + "\"-javaagent:!BCU_ROOT!\\" + MODULE_DIR + "\\" + AGENT + "\"\n";
    }

    private static String sharedLauncher() {
        return "@echo off\nsetlocal enabledelayedexpansion\n"
                + "set \"BCU_ROOT=%~dp0\"\n"
                + "if \"!BCU_ROOT:~-1!\"==\"\\\" set \"BCU_ROOT=!BCU_ROOT:~0,-1!\"\n"
                + "set \"FEATURE_DIR=!BCU_ROOT!\\" + FEATURE_DIR + "\"\n"
                + "set \"FEATURE_ARGS=\"\n"
                + "if exist \"!FEATURE_DIR!\\*.jvmargs\" for /f \"delims=\" %%g in ('dir /b /a-d /on \"!FEATURE_DIR!\\*.jvmargs\"') do (\n"
                + "  for /f \"usebackq delims=\" %%a in (\"!FEATURE_DIR!\\%%g\") do (\n"
                + "    set \"ARG=%%a\"\n"
                + "    if defined ARG if not \"!ARG:~0,1!\"==\"#\" set \"FEATURE_ARGS=!FEATURE_ARGS! !ARG!\"\n"
                + "  )\n"
                + ")\n"
                + "if not defined FEATURE_ARGS (echo [ERROR] No feature registrations found in !FEATURE_DIR!& pause& exit /b 1)\n"
                + "for /f \"usebackq delims=\" %%f in (`powershell -NoProfile -ExecutionPolicy Bypass -Command \"$d='!BCU_ROOT!'; Get-ChildItem -LiteralPath $d -Filter 'BCU*.jar' ^| Where-Object { $_.Name -notmatch 'installer|attack-ld|manual-control|backup|patch' } ^| Sort-Object LastWriteTimeUtc -Descending ^| Select-Object -First 1 -ExpandProperty FullName\"`) do set \"BCU_JAR=%%f\"\n"
                + "if not defined BCU_JAR (echo [ERROR] No BCU jar found in !BCU_ROOT!& pause& exit /b 1)\n"
                + "cd /d \"!BCU_ROOT!\"\n"
                + "java !FEATURE_ARGS! -jar \"!BCU_JAR!\" %*\n"
                + "set \"CODE=!errorlevel!\"\n"
                + "if not \"!CODE!\"==\"0\" if not defined BCU_FEATURES_HIDDEN pause\n"
                + "endlocal & exit /b %CODE%\n";
    }

    private static String hiddenLauncher() {
        return "Option Explicit\nDim sh, env, fso, root, runFile, command, code\n"
                + "Set sh = CreateObject(\"WScript.Shell\")\n"
                + "Set env = sh.Environment(\"Process\")\n"
                + "Set fso = CreateObject(\"Scripting.FileSystemObject\")\n"
                + "root = fso.GetParentFolderName(WScript.ScriptFullName)\n"
                + "runFile = fso.BuildPath(root, \"" + LAUNCHER + "\")\n"
                + "env(\"BCU_FEATURES_HIDDEN\") = \"1\"\n"
                + "command = \"cmd.exe /d /s /c \" & Chr(34) & Chr(34) & runFile & Chr(34) & Chr(34)\n"
                + "code = sh.Run(command, 0, True)\n"
                + "If code <> 0 Then MsgBox \"BCU New Features exited with code \" & code, vbExclamation, \"BCU New Features\"\n"
                + "WScript.Quit code\n";
    }

    private static String installedReadme() {
        return "BCU Attack LD Editor\n\n"
                + "Start BCU using BCU New Features.bat in the parent folder.\n"
                + "The original BCU jar is never modified.\n"
                + "Registration: " + FEATURE_DIR + "/" + REGISTRATION + "\n"
                + "Unsupported BCU versions are rejected safely by the compatibility gate.\n";
    }

    private static File installerDir() {
        try {
            URI uri = AttackLdInstaller.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI();
            File self = new File(uri);
            return self.isFile() ? self.getParentFile() : self;
        } catch (Throwable ignored) {
            return new File(".");
        }
    }

    private static void ok(String message) {
        System.out.println("[BCU Attack LD Editor] OK\n" + message);
        if (!GraphicsEnvironment.isHeadless() && !Boolean.getBoolean("attackldinstaller.nogui"))
            JOptionPane.showMessageDialog(null, message, "BCU Attack LD Editor",
                    JOptionPane.INFORMATION_MESSAGE);
    }

    private static void fail(String message) {
        System.err.println("[BCU Attack LD Editor] FAILED\n" + message);
        if (!GraphicsEnvironment.isHeadless() && !Boolean.getBoolean("attackldinstaller.nogui"))
            JOptionPane.showMessageDialog(null, message, "BCU Attack LD Editor",
                    JOptionPane.ERROR_MESSAGE);
        System.exit(1);
    }

    private static final class InstallError extends Exception {
        InstallError(String message) { super(message); }
    }

    private AttackLdInstaller() {}
}
