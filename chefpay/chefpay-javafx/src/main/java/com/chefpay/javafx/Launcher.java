package com.chefpay.javafx;

/**
 * Exists purely as the jar's {@code Main-Class} / jpackage {@code --main-class} target instead of
 * {@link ChefPayDesktopApp} itself.
 *
 * <p>{@code ChefPayDesktopApp} extends {@link javafx.application.Application} directly, which is
 * fine for {@code mvn javafx:run} (the javafx-maven-plugin sets up the module path/added-modules
 * itself), but breaks a packaged launch: when a plain classpath jar's declared main class is an
 * {@code Application} subclass, the JVM launcher applies a special check that requires JavaFX to
 * be resolvable as proper modules - and refuses to start otherwise with "JavaFX runtime components
 * are missing, and are required to run this application". jpackage's generated native launcher
 * (the {@code .exe} produced by {@code package-windows-exe.bat}) runs the shaded fat jar exactly
 * this way, off the classpath, with our own JavaFX classes/native libs bundled inside the jar
 * rather than on a real module path - so that check fails every time. Because jpackage builds a
 * windowed app with no attached console, that failure is completely silent: double-clicking the
 * installed shortcut just does nothing, with no dialog, no error, no log line anywhere.
 *
 * <p>The fix is this indirection: a plain class with a {@code main} method - not an
 * {@code Application} subclass - that just forwards to {@link ChefPayDesktopApp#main}. Because
 * {@code Launcher} itself doesn't extend {@code Application}, the JVM launcher's special check
 * never triggers, and {@code Application.launch(...)} inside {@code ChefPayDesktopApp.main} works
 * normally once already inside a running JVM. See {@code chefpay-javafx/pom.xml}'s
 * {@code mainClass} property (feeds both the javafx-maven-plugin and the shade plugin's manifest)
 * and {@code package-windows-exe.bat}'s {@code MAIN_CLASS} - both point here, not at
 * {@code ChefPayDesktopApp}, for exactly this reason. Don't repoint either back at
 * {@code ChefPayDesktopApp} directly.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        ChefPayDesktopApp.main(args);
    }
}
