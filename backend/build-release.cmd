@echo off
rem Release backend build: mvn package, replace in-jar application.properties with the clean template, copy to frontend packaging dir
cd /d %~dp0
call mvnw.cmd package -DskipTests -q
if errorlevel 1 ( echo MVN-FAILED & exit /b 1 )
if exist target\_rel rmdir /s /q target\_rel
mkdir target\_rel\BOOT-INF\classes
copy src\main\resources\application-release.properties target\_rel\BOOT-INF\classes\application.properties >nul
jar --update --file target\cyberpet-backend-0.0.1.jar -C target\_rel BOOT-INF\classes\application.properties
if errorlevel 1 ( echo JAR-PATCH-FAILED & exit /b 1 )
rmdir /s /q target\_rel
copy /y target\cyberpet-backend-0.0.1.jar ..\frontend\packaging\app.jar >nul
echo BACKEND-RELEASE-JAR-OK
