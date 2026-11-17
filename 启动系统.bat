@echo off
chcp 65001 >nul
setlocal EnableExtensions

set "ROOT=%~dp0"
set "SERVER_DIR=%ROOT%ai-resume-system\server"
set "DB_DIR=%ROOT%ai-resume-system\db"
set "MYSQL=C:\Program Files\MySQL\MySQL Server 5.5\bin\mysql.exe"
set "DB_USER=root"
set "DB_PASS="
set "DB_NAME=ai_resume"
set "APP_URL=http://localhost:8080/"

title AI 简历优化系统 - 一键启动
cls
echo ============================================================
echo    AI 简历优化系统 - 一键启动
echo    学生端：%APP_URL%
echo    管理端：%APP_URL%admin/dashboard.html
echo ============================================================
echo.

rem ================= 0/5 加载本地配置（不进入仓库）=================
rem 密钥、口令与工具链路径统一从 ai-key.local.bat 读取（该文件已被 .gitignore 忽略）
set "AI_API_KEY="
set "AI_ENDPOINT="
set "AI_MODEL="
if exist "%ROOT%ai-key.local.bat" call "%ROOT%ai-key.local.bat"
if defined DB_PASSWORD set "DB_PASS=%DB_PASSWORD%"
if not defined DB_PASS set "DB_PASS=change-me"
rem 未在本地配置中指定时，回落到环境变量或下方占位符（请按本机实际路径填写）
if not defined JAVA_HOME set "JAVA_HOME=CHANGE_ME_JDK17_HOME"
if not defined MAVEN_HOME set "MAVEN_HOME=CHANGE_ME_MAVEN_HOME"

rem ================= 1/5 工具链检查 =================
if not exist "%JAVA_HOME%\bin\java.exe" goto :err_java
if not exist "%MAVEN_HOME%\bin\mvn.cmd" goto :err_maven
if not exist "%MYSQL%" goto :err_mysql
if not exist "%SERVER_DIR%\pom.xml" goto :err_dir
set "PATH=%JAVA_HOME%\bin;%MAVEN_HOME%\bin;%PATH%"
set "MAVEN_OPTS=%MAVEN_OPTS% -Dfile.encoding=UTF-8"
echo [1/5] 工具链就绪：JDK 17 / Maven 3.9.11 / MySQL 5.5

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
echo [3/5] 8080 端口已被占用，PID=%PID%，服务可能已在运行
choice /C YN /N /T 15 /D Y /M "      停止旧进程并重新启动吗？Y=重启 N=只打开页面 : "
if errorlevel 2 goto :open_only
echo       正在停止 PID %PID% ...
taskkill /F /PID %PID% >nul 2>&1
timeout /t 2 /nobreak >nul
goto :db_check
:port_free
echo [3/5] 8080 端口空闲

rem ================= 4/5 数据库检查 =================
:db_check
"%MYSQL%" -u%DB_USER% -p%DB_PASS% -e "select 1" >nul 2>&1
if errorlevel 1 goto :err_db_server
set "TABLES=0"
"%MYSQL%" -u%DB_USER% -p%DB_PASS% -N -B -e "select count(*) from information_schema.tables where table_schema='%DB_NAME%'" >"%TEMP%\ai_tables.txt" 2>nul
set /p TABLES=<"%TEMP%\ai_tables.txt"
del "%TEMP%\ai_tables.txt" >nul 2>&1
if not "%TABLES%"=="0" goto :db_ok
echo [4/5] 数据库 %DB_NAME% 不存在或为空，正在初始化...
pushd "%DB_DIR%"
"%MYSQL%" -u%DB_USER% -p%DB_PASS% --default-character-set=utf8mb4 -e "source schema.sql"
if errorlevel 1 goto :err_init
"%MYSQL%" -u%DB_USER% -p%DB_PASS% --default-character-set=utf8mb4 -e "source data.sql"
if errorlevel 1 goto :err_init
popd
echo       初始化完成：10 张表 + 演示数据
goto :start_app
:db_ok
echo [4/5] 数据库 %DB_NAME% 已就绪，共 %TABLES% 张表

rem ================= 5/5 启动服务 =================
:start_app
echo [5/5] 正在启动服务，首次构建约需 1 到 2 分钟...
echo       运行模式：AI（profile=ai），端口 8080
echo       日志出现 Started ResumeApplication 后会自动打开浏览器
echo.
start "" /min powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -Command "for($i=0;$i -lt 240;$i++){try{$r=Invoke-WebRequest -Uri '%APP_URL%' -UseBasicParsing -TimeoutSec 2;if($r.StatusCode -eq 200){Start-Process '%APP_URL%';break}}catch{};Start-Sleep -Seconds 1}"

set "SPRING_PROFILES_ACTIVE=ai"
set "AI_ENABLED=true"
set "SERVER__PORT=8080"
set "SERVER_PORT=8080"
rem 以环境变量形式传给 Spring Boot，避免明文口令出现在任何配置文件里
set "DB_USERNAME=%DB_USER%"
set "DB_PASSWORD=%DB_PASS%"

pushd "%SERVER_DIR%"
call "%MAVEN_HOME%\bin\mvn.cmd" spring-boot:run
popd

echo.
echo 服务已退出。关闭本窗口即可；如需再次启动，请重新双击本脚本。
pause
exit /b 0

rem ================= 只打开页面 =================
:open_only
echo       直接打开页面：%APP_URL%
start "" "%APP_URL%"
timeout /t 2 /nobreak >nul
exit /b 0

rem ================= 子过程：取 8080 监听进程 =================
:get_port_pid
set "PID="
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /C:":8080" ^| findstr /C:"LISTENING"') do set "PID=%%p"
goto :eof

rem ================= 错误处理 =================
:err_java
echo [错误] 未找到 JDK 17：%JAVA_HOME%\bin\java.exe
goto :die
:err_maven
echo [错误] 未找到 Maven：%MAVEN_HOME%\bin\mvn.cmd
goto :die
:err_mysql
echo [错误] 未找到 MySQL 客户端：%MYSQL%
goto :die
:err_dir
echo [错误] 未找到项目目录：%SERVER_DIR%\pom.xml
goto :die
:err_db_server
echo [错误] 无法连接 MySQL 3306，请先启动 MySQL 服务
goto :die
:err_init
echo [错误] 数据库初始化失败，请查看上方 MySQL 输出
popd
goto :die

:die
echo.
pause
exit /b 1
