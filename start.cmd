@echo off
chcp 65001 >nul
title Trouble Lab - 生产故障演练实验室 (port 8091)
cd /d %~dp0

echo ============================================
echo   Trouble Lab 生产故障演练实验室
echo   端口: 8091   状态面板: /issue/status
echo   堆: -Xmx512m   元空间: 128m
echo ============================================
echo.

D:\workspace\.toolchain\jdk-21.0.12.1+1\bin\java.exe ^
  -Xms256m -Xmx512m ^
  -XX:MaxMetaspaceSize=128m ^
  -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=logs ^
  -Dfile.encoding=UTF-8 ^
  -jar target\trouble-lab-1.0.0.jar --server.port=8091

pause
