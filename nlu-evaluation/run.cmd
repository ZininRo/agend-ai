@echo off
setlocal
cd /d "%~dp0..\backend"
call mvnw.cmd -q -DskipTests spring-boot:run -Dspring-boot.run.main-class=com.example.agend_ai.evaluation.NluEvaluationCli -Dspring-boot.run.arguments="%*"
exit /b %ERRORLEVEL%
