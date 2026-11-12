@echo off
REM Build output lives on F: but stays visible inside the project as app\build.
REM AGP 9 refuses to create unit-test tasks when buildDir sits on another drive,
REM so a junction is the only way to keep both "small C:" and working tests.
setlocal
set APP_BUILD=%~dp0app\build
set ROOT_BUILD=%~dp0build
set T1=F:\gradle-builds\todo-native\app
set T2=F:\gradle-builds\todo-native\DailyTaskList

if not exist "%T1%" mkdir "%T1%"
if not exist "%T2%" mkdir "%T2%"

if exist "%APP_BUILD%" (
  echo app\build already exists, skip
) else (
  mklink /J "%APP_BUILD%" "%T1%"
)

if exist "%ROOT_BUILD%" (
  echo build already exists, skip
) else (
  mklink /J "%ROOT_BUILD%" "%T2%"
)

echo done.
