@echo off
cd /d "%~dp0"
where docker >nul 2>nul
if errorlevel 1 (
  echo Docker with Compose v2 is required. See README.md.
  exit /b 1
)
echo TableSignal will be available at http://127.0.0.1:8080 after startup.
echo Press Ctrl+C to stop. The database volume is preserved.
docker compose up --build
