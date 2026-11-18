@echo off
chcp 936 >nul
setlocal EnableExtensions

set "ROOT=%~dp0"
set "SERVER_DIR=%ROOT%ai-resume-system\server"
set "DB_DIR=%ROOT%ai-resume-system\db"
set "DB_USER=root"
set "DB_NAME=ai_resume"
set "APP_PORT=8080"
set "APP_URL=http://localhost:%APP_PORT%/"

set "JAVA_HOME="
set "MAVEN_HOME="
set "MYSQL="
set "DB_PASS="
set "JAVA_VER=?"

title AI 简历优化系统 - 一键启动
cls
echo ============================================================
echo    AI 简历优化系统 - 一键启动
echo    学生端：%APP_URL%
echo    管理端：%APP_URL%admin/dashboard.html
echo ============================================================
echo.

rem ================= 0/5 加载本地配置（不进入仓库）=================
rem 密钥、口令与可选的工具链路径从 ai-key.local.bat 读取（该文件已被 .gitignore 忽略）
set "AI_API_KEY="
set "AI_ENDPOINT="
set "AI_MODEL="
if exist "%ROOT%ai-key.local.bat" call "%ROOT%ai-key.local.bat"
if defined DB_PASSWORD set "DB_PASS=%DB_PASSWORD%"

rem ================= 1/5 工具链检查（自动探测，不依赖写死路径）=================
call :resolve_java
if not defined JAVA_HOME goto :err_java
call :resolve_maven
if not defined MAVEN_HOME goto :err_maven
call :resolve_mysql
if not defined MYSQL goto :err_mysql
if not exist "%SERVER_DIR%\pom.xml" goto :err_dir

set "PATH=%JAVA_HOME%\bin;%MAVEN_HOME%\bin;%PATH%"
set "MAVEN_OPTS=%MAVEN_OPTS% -Dfile.encoding=UTF-8"
echo [1/5] Java  %JAVA_VER%
echo       JAVA_HOME   %JAVA_HOME%
echo       MAVEN_HOME  %MAVEN_HOME%
echo       MySQL       %MYSQL%

rem ================= 2/5 AI 密钥状态 =================
if not defined AI_API_KEY goto :no_key
echo [2/5] 已加载本地 AI 密钥：%AI_ENDPOINT%
echo       分析模型 %AI_MODEL%
goto :check_port
:no_key
echo [2/5] 未配置 AI 密钥，将自动回落规则模式

rem ================= 3/5 端口检查 =================
:check_port
call :get_port_pid
if not defined PID goto :port_free
echo [3/5] %APP_PORT% 端口已被占用，PID=%PID%，服务可能已在运行
"%SystemRoot%\System32\choice.exe" /C YN /N /T 15 /D Y /M "      停止旧进程并重新启动吗？Y=重启 N=只打开页面 : "
if errorlevel 2 goto :open_only
echo       正在停止 PID %PID% ...
taskkill /F /PID %PID% >nul 2>&1
"%SystemRoot%\System32\timeout.exe" /t 2 /nobreak >nul
goto :db_check
:port_free
echo [3/5] %APP_PORT% 端口空闲

rem ================= 4/5 数据库检查 =================
:db_check
set "MYSQL_AUTH=-u%DB_USER%"
if defined DB_PASS set "MYSQL_AUTH=%MYSQL_AUTH% -p%DB_PASS%"
"%MYSQL%" %MYSQL_AUTH% -e "select 1" >nul 2>&1
if errorlevel 1 goto :err_db_server
set "TABLES=0"
"%MYSQL%" %MYSQL_AUTH% -N -B -e "select count(*) from information_schema.tables where table_schema='%DB_NAME%'" >"%TEMP%\ai_tables.txt" 2>nul
if exist "%TEMP%\ai_tables.txt" set /p TABLES=<"%TEMP%\ai_tables.txt"
del "%TEMP%\ai_tables.txt" >nul 2>&1
if not "%TABLES%"=="0" goto :db_ok
echo [4/5] 数据库 %DB_NAME% 不存在或为空，正在初始化...
call :init_db
if errorlevel 1 goto :err_init
echo       初始化完成：11 张表 + 演示数据
goto :start_app
:db_ok
echo [4/5] 数据库 %DB_NAME% 已就绪，共 %TABLES% 张表

rem ================= 5/5 启动服务 =================
:start_app
echo [5/5] 正在启动服务，首次构建约需 1 到 2 分钟...
echo       运行模式：AI（profile=ai，未配置密钥时自动降级规则模式），端口 %APP_PORT%
echo       日志出现 Started ResumeApplication 后会自动打开浏览器
echo.
start "" /min powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -Command "for($i=0;$i -lt 240;$i++){try{$r=Invoke-WebRequest -Uri '%APP_URL%' -UseBasicParsing -TimeoutSec 2;if($r.StatusCode -eq 200){Start-Process '%APP_URL%';break}}catch{};Start-Sleep -Seconds 1}"

set "SPRING_PROFILES_ACTIVE=ai"
set "AI_ENABLED=true"
set "SERVER_PORT=%APP_PORT%"
rem 以环境变量形式传给 Spring Boot，避免明文口令出现在任何配置文件里
set "DB_USERNAME=%DB_USER%"
set "DB_PASSWORD=%DB_PASS%"

pushd "%SERVER_DIR%"
call "%MAVEN_HOME%\bin\mvn.cmd" spring-boot:run "-Dspring-boot.run.arguments=--server.port=%APP_PORT% --spring.profiles.active=ai --logging.charset.console=GBK"
set "MVN_RC=%ERRORLEVEL%"
popd

echo.
if not "%MVN_RC%"=="0" echo [提示] 构建或启动失败（退出码 %MVN_RC%），请查看上方 Maven 输出。
echo 服务已退出。关闭本窗口即可；如需再次启动，请重新双击本脚本。
pause
exit /b %MVN_RC%

rem ================= 只打开页面 =================
:open_only
echo       直接打开页面：%APP_URL%
start "" "%APP_URL%"
"%SystemRoot%\System32\timeout.exe" /t 2 /nobreak >nul
exit /b 0

rem ================= 子过程：初始化数据库 =================
:init_db
pushd "%DB_DIR%"
"%MYSQL%" %MYSQL_AUTH% --default-character-set=utf8mb4 < schema.sql
if errorlevel 1 goto :init_fail
"%MYSQL%" %MYSQL_AUTH% --default-character-set=utf8mb4 < data.sql
if errorlevel 1 goto :init_fail
popd
exit /b 0
:init_fail
popd
exit /b 1

rem ================= 子过程：取端口监听进程 =================
:get_port_pid
set "PID="
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /C:"LISTENING" ^| findstr /C:":%APP_PORT% "') do set "PID=%%p"
goto :eof

rem ================= 子过程：探测 JDK（要求 17 及以上）=================
:resolve_java
if not defined JAVA_HOME goto :rj_scan
if not exist "%JAVA_HOME%\bin\java.exe" goto :rj_clear
call :java_ok "%JAVA_HOME%"
if not errorlevel 1 goto :eof
:rj_clear
set "JAVA_HOME="
:rj_scan
for /d %%d in ("%ProgramFiles%\Java\*" "%ProgramFiles%\Eclipse Adoptium\*" "D:\Program Files\Java\*" "%USERPROFILE%\.jdks\*") do call :try_java "%%~fd"
if defined JAVA_HOME goto :eof
for /f "delims=" %%p in ('where java.exe 2^>nul') do call :try_java_path "%%~fp"
goto :eof

:try_java
if defined JAVA_HOME goto :eof
if not exist "%~1\bin\java.exe" goto :eof
call :java_ok "%~1"
if errorlevel 1 goto :eof
set "JAVA_HOME=%~1"
goto :eof

:try_java_path
if defined JAVA_HOME goto :eof
set "CAND=%~dp1"
set "CAND=%CAND:~0,-1%"
for %%d in ("%CAND%\..") do call :try_java "%%~fd"
goto :eof

:java_ok
set "JV="
"%~1\bin\java.exe" -version >"%TEMP%\ai_java_ver.txt" 2>&1
for /f "tokens=3" %%v in ('findstr /i "version" "%TEMP%\ai_java_ver.txt"') do if not defined JV set "JV=%%~v"
del "%TEMP%\ai_java_ver.txt" >nul 2>&1
if not defined JV exit /b 1
set "JV1="
set "JV2="
for /f "tokens=1 delims=." %%a in ("%JV%") do set "JV1=%%a"
for /f "tokens=2 delims=." %%b in ("%JV%") do set "JV2=%%b"
if "%JV1%"=="1" set "JVMAJ=%JV2%"
if "%JV1%"=="1" goto :java_cmp
set "JVMAJ=%JV1%"
:java_cmp
if not defined JVMAJ exit /b 1
if %JVMAJ% GEQ 17 set "JAVA_VER=%JV%"
if %JVMAJ% GEQ 17 exit /b 0
exit /b 1

rem ================= 子过程：探测 Maven =================
:resolve_maven
if not defined MAVEN_HOME goto :rm_scan
if not exist "%MAVEN_HOME%\bin\mvn.cmd" set "MAVEN_HOME="
if defined MAVEN_HOME goto :eof
:rm_scan
for /f "delims=" %%m in ('where mvn.cmd 2^>nul') do call :try_maven "%%~fpm"
if defined MAVEN_HOME goto :eof
for /d %%d in ("%ProgramFiles%\apache-maven-*" "%ProgramFiles%\maven-*" "D:\Program Files\maven-*" "D:\Program Files\apache-maven-*" "C:\apache-maven-*" "%USERPROFILE%\apache-maven-*") do call :try_maven_dir "%%~fd"
goto :eof

:try_maven
if defined MAVEN_HOME goto :eof
for %%d in ("%~dp1..") do call :try_maven_dir "%%~fd"
goto :eof

:try_maven_dir
if defined MAVEN_HOME goto :eof
if exist "%~1\bin\mvn.cmd" set "MAVEN_HOME=%~1"
goto :eof

rem ================= 子过程：探测 MySQL 客户端 =================
:resolve_mysql
if defined MYSQL if exist "%MYSQL%" goto :eof
for %%p in ("%ProgramFiles%\MySQL\MySQL Server 5.5\bin\mysql.exe") do if exist "%%~fp" set "MYSQL=%%~fp"
if defined MYSQL goto :eof
for /f "delims=" %%m in ('where mysql.exe 2^>nul') do if not defined MYSQL set "MYSQL=%%~fm"
if defined MYSQL goto :eof
for /d %%d in ("%ProgramFiles%\MySQL\MySQL Server*" "D:\Program Files\MySQL\MySQL Server*") do if not defined MYSQL if exist "%%~fd\bin\mysql.exe" set "MYSQL=%%~fd\bin\mysql.exe"
goto :eof

rem ================= 错误处理 =================
:err_java
echo [错误] 未找到 JDK 17 及以上版本。
echo        请安装 JDK 17+，或在 ai-key.local.bat 中设置 JAVA_HOME 指向安装目录。
goto :die
:err_maven
echo [错误] 未找到 Maven（mvn.cmd）。
echo        请确认 Maven 已安装并加入 PATH，或在 ai-key.local.bat 中设置 MAVEN_HOME。
goto :die
:err_mysql
echo [错误] 未找到 MySQL 命令行客户端 mysql.exe。
echo        请确认已安装 MySQL 5.5 客户端，或在 PATH 中加入其 bin 目录。
goto :die
:err_dir
echo [错误] 未找到项目目录：%SERVER_DIR%\pom.xml
goto :die
:err_db_server
echo [错误] 无法连接 MySQL 3306（账号 %DB_USER%）。
echo        请先启动 MySQL 服务，并确认 ai-key.local.bat 中的 DB_USERNAME / DB_PASSWORD 正确。
goto :die
:err_init
echo [错误] 数据库初始化失败，请查看上方 MySQL 输出。
echo        常见原因：口令错误、MySQL 未启动、库名冲突。
goto :die

:die
echo.
pause
exit /b 1
