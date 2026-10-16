@echo off
rem Installs Python 3.13 for the "no-python" download of CraftPy (the normal download has Python inside: you do not
rem need this then). Installs for your user only, no administrator rights, and adds Python to PATH.
setlocal

echo Checking for Python 3.13 or newer ...
for %%P in (python python3) do (
    %%P -c "import sys; sys.exit(0 if sys.version_info >= (3, 13) else 1)" >nul 2>&1 && (
        echo Found: & %%P --version
        echo Python is ready. Start Minecraft.
        goto :done
    )
)

where winget >nul 2>&1
if %errorlevel%==0 (
    echo Installing Python 3.13 with winget ...
    winget install -e --id Python.Python.3.13 --scope user --silent --accept-package-agreements --accept-source-agreements
    if %errorlevel%==0 goto :installed
    echo winget did not work, downloading from python.org instead ...
)

set "VERSION=3.13.5"
set "SETUP=%TEMP%\python-%VERSION%-amd64.exe"
echo Downloading Python %VERSION% from python.org ...
curl -L --fail -o "%SETUP%" "https://www.python.org/ftp/python/%VERSION%/python-%VERSION%-amd64.exe"
if errorlevel 1 (
    echo The download failed. Install Python 3.13 yourself from https://www.python.org/downloads/
    echo and tick "Add python.exe to PATH" in the installer.
    goto :fail
)
echo Installing (for this user, added to PATH) ...
"%SETUP%" /quiet InstallAllUsers=0 PrependPath=1 Include_test=0 Include_launcher=1
if errorlevel 1 (
    echo The installer reported an error. Run "%SETUP%" yourself and tick "Add python.exe to PATH".
    goto :fail
)
del "%SETUP%" >nul 2>&1

:installed
echo.
echo Python is installed. Close this window, then start Minecraft again
echo (a new start is needed so Minecraft sees the new PATH).
goto :done

:fail
echo.
echo Python was not installed. In CraftPy's config you can also set pythonPath to a python.exe.
:done
echo.
pause
endlocal
