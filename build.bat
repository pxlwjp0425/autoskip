@echo off
rem 快跳 AutoSkip 命令行编译脚本
setlocal

set "JAVA_HOME=D:\dev\jdk21"
set "ANDROID_HOME=D:\Android\Sdk"
set "GRADLE_USER_HOME=D:\dev\gradle-home"
set "PATH=%JAVA_HOME%\bin;%PATH%"

set "PROJ=%~dp0"

echo [1/2] 编译 release APK ...
call "D:\dev\gradle-8.14.3\bin\gradle.bat" -p "%PROJ%" assembleRelease
if errorlevel 1 (
    echo 编译失败，请检查上面的错误信息。
    exit /b 1
)

echo [2/2] 完成。产物：
echo   %PROJ%app\build\outputs\apk\release\app-release.apk
endlocal
