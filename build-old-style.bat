@echo off
echo ========================================
echo Building SDRTrunk - VoxSend Edition
echo Java 22 with JavaFX
echo ========================================
echo.

REM Change to script directory
cd /d "%~dp0"

REM Set Java paths
set JAVA_HOME=C:\Java\LibericaJDK-23-Full
set PATH=%JAVA_HOME%\bin;%PATH%

echo Step 1: Building application JAR...
call gradlew.bat -Dorg.gradle.java.home=C:\\Java\\LibericaJDK-23-Full clean build installDist -x test
if %ERRORLEVEL% NEQ 0 goto :error
echo.

echo Step 2: Creating custom runtime with jlink...
if exist build\custom-jre rmdir /s /q build\custom-jre

jlink --module-path "%JAVA_HOME%\jmods;C:\Java\javafx-jmods-23" ^
      --add-modules java.base,java.desktop,java.logging,java.management,java.naming,java.prefs,java.sql,java.xml,jdk.crypto.ec,jdk.incubator.vector,jdk.accessibility,jdk.unsupported,java.scripting,jdk.jsobject,java.compiler,javafx.base,javafx.controls,javafx.fxml,javafx.graphics,javafx.media,javafx.swing,javafx.web ^
      --bind-services ^
      --output build\custom-jre ^
      --strip-debug ^
      --compress=2 ^
      --no-header-files ^
      --no-man-pages

if %ERRORLEVEL% NEQ 0 goto :error
echo.

echo Step 3: Creating distribution structure...
if exist build\sdr-trunk-package rmdir /s /q build\sdr-trunk-package
mkdir build\sdr-trunk-package

REM Copy the entire JRE to root (this becomes bin, lib, conf, legal)
echo Copying JRE structure...
xcopy /E /I /Y build\custom-jre\* build\sdr-trunk-package\

REM Copy application JARs to lib folder
echo Copying application JARs...
copy build\install\sdr-trunk\lib\*.jar build\sdr-trunk-package\lib\

echo Step 4: Creating launcher script...
(
echo @if "%%DEBUG%%" == "" @echo off
echo @rem ##########################################################################
echo @rem
echo @rem  sdr-trunk startup script for Windows
echo @rem
echo @rem ##########################################################################
echo @rem Set local scope for the variables with windows NT shell
echo if "%%OS%%"=="Windows_NT" setlocal
echo set DIRNAME=%%~dp0
echo if "%%DIRNAME%%" == "" set DIRNAME=.
echo set APP_BASE_NAME=%%~n0
echo set APP_HOME=%%DIRNAME%%..
echo @rem Add default JVM options here. You can also use JAVA_OPTS and SDR_TRUNK_OPTS to pass JVM options to this script.
echo set DEFAULT_JVM_OPTS="--add-exports=javafx.base/com.sun.javafx.event=ALL-UNNAMED" "--add-exports=java.desktop/com.sun.java.swing.plaf.windows=ALL-UNNAMED" "--add-modules=jdk.incubator.vector" "--enable-preview" "--enable-native-access=ALL-UNNAMED" "--enable-native-access=javafx.graphics" "-Dprism.order=sw"
echo set JAVA_HOME="%%APP_HOME%%"
echo set JAVA_EXE=%%JAVA_HOME%%/bin/java.exe
echo set JAVA_EXE="%%JAVA_EXE:"=%%"
echo if exist %%JAVA_EXE%% goto init
echo echo.
echo echo ERROR: The directory %%JAVA_HOME%% does not contain a valid Java runtime for your platform.
echo echo.
echo echo Please set the JAVA_HOME variable in your environment to match the
echo echo location of your Java installation.
echo goto fail
echo :init
echo @rem Get command-line arguments, handling Windows variants
echo if not "%%OS%%" == "Windows_NT" goto win9xME_args
echo :win9xME_args
echo @rem Slurp the command line arguments.
echo set CMD_LINE_ARGS=
echo set _SKIP=2
echo :win9xME_args_slurp
echo if "x%%~1" == "x" goto execute
echo set CMD_LINE_ARGS=%%*
echo :execute
echo @rem Setup the command line
echo set CLASSPATH="%%JAVA_HOME:"=%%/lib/*"
echo @rem Execute sdr-trunk
echo %%JAVA_EXE%% %%DEFAULT_JVM_OPTS%% %%JAVA_OPTS%% %%SDR_TRUNK_OPTS%%  -classpath %%CLASSPATH%% io.github.dsheirer.gui.SDRTrunk %%CMD_LINE_ARGS%%
echo :end
echo @rem End local scope for the variables with windows NT shell
echo if "%%ERRORLEVEL%%"=="0" goto mainEnd
echo :fail
echo rem Set variable SDR_TRUNK_EXIT_CONSOLE if you need the _script_ return code instead of
echo rem the _cmd.exe /c_ return code!
echo if  not "" == "%%SDR_TRUNK_EXIT_CONSOLE%%" exit 1
echo exit /b 1
echo :mainEnd
echo if "%%OS%%"=="Windows_NT" endlocal
echo :omega
) > build\sdr-trunk-package\bin\sdr-trunk.bat

echo Step 5: Creating release file...
(
echo JAVA_VERSION="22"
echo IMPLEMENTOR="BellSoft"
) > build\sdr-trunk-package\release

echo Step 6: Creating distribution ZIP...
cd build
if exist sdr-trunk-windows-x86_64-v0.6.2-voxsend.zip del sdr-trunk-windows-x86_64-v0.6.2-voxsend.zip
powershell -command "Compress-Archive -Path sdr-trunk-package -DestinationPath sdr-trunk-windows-x86_64-v0.6.2-voxsend.zip -Force"
cd ..

echo ========================================
echo SUCCESS! Package created:
echo build\sdr-trunk-windows-x86_64-v0.6.2-voxsend.zip
echo.
echo Structure:
echo - bin\sdr-trunk.bat (launcher with JavaFX fallback)
echo - bin\*.dll (Java 22 + JavaFX native libraries)
echo - lib\*.jar (all application JARs)
echo - conf\ legal\ (JRE configuration)
echo.
echo Features:
echo - VoxSend 5-stage audio processing chain
echo - Input Gain, Low-Pass, De-emphasis, Voice Enhancement, Intelligent Squelch
echo - Self-contained (no Java installation needed)
echo ========================================
goto :end

:error
echo ========================================
echo BUILD FAILED - Check errors above
echo ========================================

:end
pause
