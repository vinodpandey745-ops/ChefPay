@echo off
REM ChefPay - build a native Windows installer (.exe) from the packaged client jar.
REM Run this from the chefpay-javafx folder AFTER "mvn clean package" has succeeded there.
REM See docs/DEPLOYMENT.md ("Packaging the client as a Windows .exe") for the full walkthrough,
REM including one-time prerequisites (JDK 21 with jpackage, and the WiX Toolset v3 for --type exe).

setlocal

set JAR_NAME=chefpay-javafx-1.0.0-SNAPSHOT.jar
REM Launcher, not ChefPayDesktopApp itself - a packaged app whose main class extends
REM javafx.application.Application directly fails to start with no visible error (jpackage builds
REM a windowed app with no console to print the "JavaFX runtime components are missing" message
REM to). See Launcher.java's javadoc for the full explanation.
set MAIN_CLASS=com.chefpay.javafx.Launcher
set APP_NAME=ChefPay
REM jpackage requires a plain numeric version (no "-SNAPSHOT") for a Windows installer.
set APP_VERSION=1.0.0

if not exist target\%JAR_NAME% (
    echo.
    echo ERROR: target\%JAR_NAME% not found - run "mvn clean package" in this folder first.
    echo.
    exit /b 1
)

if "%JAVA_HOME%"=="" (
    echo.
    echo ERROR: JAVA_HOME is not set - point it at your JDK 21 install first, e.g.:
    echo   set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.x
    echo.
    exit /b 1
)

echo Building %APP_NAME% installer (this can take a minute or two)...

REM --runtime-image bundles the FULL JDK rather than a jlink-trimmed one - larger installer
REM (~150-200 MB) but avoids any risk of jpackage's automatic module-detection missing something
REM this jar needs at runtime (it inspects a plain fat jar with jdeps, which isn't always perfectly
REM reliable for reflection-heavy libraries like Jackson/JavaFX FXML) - the safer choice for a v1.
"%JAVA_HOME%\bin\jpackage" ^
    --type exe ^
    --input target ^
    --main-jar %JAR_NAME% ^
    --main-class %MAIN_CLASS% ^
    --name %APP_NAME% ^
    --app-version %APP_VERSION% ^
    --vendor "ChefPay" ^
    --dest dist ^
    --win-menu ^
    --win-shortcut ^
    --win-dir-chooser ^
    --runtime-image "%JAVA_HOME%"

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo jpackage failed - see docs/DEPLOYMENT.md's troubleshooting notes ^(most commonly: WiX
    echo Toolset v3 isn't installed, or isn't on PATH^).
    exit /b 1
)

echo.
echo Done - installer is in dist\%APP_NAME%-%APP_VERSION%.exe
echo Remember to also copy chefpay-client.properties.example next to the installed app (renamed to
echo chefpay-client.properties, edited with your server's address) - see docs/DEPLOYMENT.md.

endlocal
