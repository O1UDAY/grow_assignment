@echo off
setlocal
set "WRAPPER=%~dp0gradle\wrapper\gradle-wrapper.jar"
if not exist "%WRAPPER%" (
    powershell.exe -NoProfile -Command "$ErrorActionPreference='Stop'; $tmp=[IO.Path]::GetTempFileName(); try { Invoke-WebRequest -UseBasicParsing 'https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar' -OutFile $tmp; if ((Get-FileHash $tmp).Hash -ne '498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17') { throw 'Wrapper checksum mismatch' }; Copy-Item -LiteralPath $tmp -Destination $env:WRAPPER } finally { Remove-Item -LiteralPath $tmp -ErrorAction SilentlyContinue }"
    if errorlevel 1 exit /b 1
)
set "JAVA_CMD=java"
if defined JAVA_HOME set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
"%JAVA_CMD%" -classpath "%WRAPPER%" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
